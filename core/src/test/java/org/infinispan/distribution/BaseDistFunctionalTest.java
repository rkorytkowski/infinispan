/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.distribution;

import org.infinispan.Cache;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.commons.hash.MurmurHash3;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.ImmortalCacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.MortalCacheEntry;
import org.infinispan.distribution.group.Grouper;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.distribution.ch.DefaultConsistentHashFactory;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.TestingUtil;
import org.infinispan.transaction.LockingMode;
import org.infinispan.test.fwk.TransportFlags;
import org.infinispan.util.Util;
import org.infinispan.util.concurrent.IsolationLevel;

import javax.transaction.TransactionManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;


public abstract class BaseDistFunctionalTest extends MultipleCacheManagersTest {
   protected String cacheName;
   protected int INIT_CLUSTER_SIZE = 4;
   protected Cache<Object, String> c1 = null, c2 = null, c3 = null, c4 = null;
   protected ConfigurationBuilder configuration;
   protected List<Cache<Object, String>> caches;
   protected List<Address> cacheAddresses;
   protected boolean sync = true;
   protected boolean tx = false;
   protected boolean testRetVals = true;
   protected boolean l1CacheEnabled = true;
   protected boolean l1OnRehash = false;
   protected int l1Threshold = 5;
   protected boolean performRehashing = false;
   protected boolean batchingEnabled = false;
   protected int numOwners = 2;
   protected int lockTimeout = 45;
   protected boolean groupsEnabled = false;
   protected List<Grouper<?>> groupers;
   protected LockingMode lockingMode;
   protected boolean supportConcurrentWrites = true;

   protected void createCacheManagers() throws Throwable {
      cacheName = "dist";
      configuration = buildConfiguration();
      // Create clustered caches with failure detection protocols on
      caches = createClusteredCaches(INIT_CLUSTER_SIZE, cacheName, configuration,
                                     new TransportFlags().withFD(false));

      if (INIT_CLUSTER_SIZE > 0) c1 = caches.get(0);
      if (INIT_CLUSTER_SIZE > 1) c2 = caches.get(1);
      if (INIT_CLUSTER_SIZE > 2) c3 = caches.get(2);
      if (INIT_CLUSTER_SIZE > 3) c4 = caches.get(3);

      cacheAddresses = new ArrayList<Address>(INIT_CLUSTER_SIZE);
      for (Cache cache : caches) {
         EmbeddedCacheManager cacheManager = cache.getCacheManager();
         cacheAddresses.add(cacheManager.getAddress());
      }
   }

   protected ConfigurationBuilder buildConfiguration() {
      ConfigurationBuilder configuration = getDefaultClusteredCacheConfig(sync ? CacheMode.DIST_SYNC : CacheMode.DIST_ASYNC, tx);
      configuration.clustering().stateTransfer().fetchInMemoryState(performRehashing);
      if (lockingMode != null) {
         configuration.transaction().lockingMode(lockingMode);
      }
      configuration.clustering().hash().numOwners(numOwners);
      if (!testRetVals) {
         configuration.unsafe().unreliableReturnValues(true);
         // we also need to use repeatable read for tests to work when we dont have reliable return values, since the
         // tests repeatedly queries changes
         configuration.locking().isolationLevel(IsolationLevel.REPEATABLE_READ);
      }
      if (tx) {
         configuration.invocationBatching().enable();
      }
      if (sync) configuration.clustering().sync().replTimeout(60, TimeUnit.SECONDS);
      configuration.locking().lockAcquisitionTimeout(lockTimeout, TimeUnit.SECONDS);
      configuration.clustering().l1().enabled(l1CacheEnabled);
      if (groupsEnabled) {
          configuration.clustering().hash().groups().enabled(true);
          configuration.clustering().hash().groups().withGroupers(groupers);
      }
      if (l1CacheEnabled) configuration.clustering().l1().onRehash(l1OnRehash).invalidationThreshold(l1Threshold);
      configuration.locking().supportsConcurrentUpdates(supportConcurrentWrites);
      return configuration;
   }

   protected static ConsistentHash createNewConsistentHash(List<Address> servers) {
      try {
         ConfigurationBuilder builder = new ConfigurationBuilder();
         builder.clustering().hash()
            .consistentHash(null);
         // TODO Revisit after we have replaced the CH with the CHFactory in the configuration
         return new DefaultConsistentHashFactory().create(new MurmurHash3(), 2, 10, servers);
      } catch (RuntimeException re) {
         throw re;
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   // ----------------- HELPERS ----------------

   protected void waitForJoinTasksToComplete(long timeout, Cache... joiners) {
      long giveupTime = System.currentTimeMillis() + timeout;
      while (System.currentTimeMillis() < giveupTime) {
         boolean allOK = true;
         for (Cache c : joiners) {
            DistributionManagerImpl dmi = (DistributionManagerImpl) getDistributionManager(c);
            allOK &= dmi.isJoinComplete();
         }
         if (allOK) return;
         TestingUtil.sleepThread(100);
      }
      throw new RuntimeException("Some caches have not finished rehashing after " + Util.prettyPrintTime(timeout));
   }


   protected void initAndTest() {
      for (Cache<Object, String> c : caches) assert c.isEmpty();

      c1.put("k1", "value");
      asyncWait("k1", PutKeyValueCommand.class);
      assertOnAllCachesAndOwnership("k1", "value");
   }

   protected Address addressOf(Cache<?, ?> cache) {
      return DistributionTestHelper.addressOf(cache);
   }

   protected Cache<Object, String> getFirstNonOwner(String key) {
      return getNonOwners(key)[0];
   }
   
   protected Cache<Object, String> getFirstOwner(String key) {
      return getOwners(key)[0];
   }

   protected Cache<Object, String> getSecondNonOwner(String key) {
      return getNonOwners(key)[1];
   }

   protected void assertOnAllCachesAndOwnership(Object key, String value) {
      assertOwnershipAndNonOwnership(key, l1CacheEnabled);
      // checking the values will bring the keys to L1, so we want to do it after checking ownership
      assertOnAllCaches(key, value);
   }

   protected void assertRemovedOnAllCaches(Object key) {
      assertOnAllCaches(key, null);
   }

   protected void assertOnAllCaches(Object key, String value) {
      for (Cache<Object, String> c : caches) {
         Object realVal = c.get(key);
         if (value == null) {
            assert realVal == null : "Expecting [" + key + "] to equal [" + value + "] on cache ["
                  + addressOf(c) + "] but was [" + realVal + "]. Owners are " + Arrays.toString(getOwners(key));
         } else {
            assert value.equals(realVal) : "Expecting [" + key + "] to equal [" + value + "] on cache ["
                  + addressOf(c) + "] but was [" + realVal + "]";
         }
      }
      // Allow some time for all ClusteredGetCommands to finish executing
      TestingUtil.sleepThread(1000);
   }

   protected void assertOwnershipAndNonOwnership(Object key, boolean allowL1) {
      for (Cache<Object, String> c : caches) {
         DataContainer dc = c.getAdvancedCache().getDataContainer();
         InternalCacheEntry ice = dc.get(key);
         if (isOwner(c, key)) {
            assert ice != null : "Fail on owner cache " + addressOf(c) + ": dc.get(" + key + ") returned null!";
            assert ice instanceof ImmortalCacheEntry : "Fail on owner cache " + addressOf(c) + ": dc.get(" + key + ") returned " + safeType(ice);
         } else {
            if (allowL1) {
               assert ice == null || ice instanceof MortalCacheEntry : "Fail on non-owner cache " + addressOf(c) + ": dc.get(" + key + ") returned " + safeType(ice);
            } else {
               assert ice == null : "Fail on non-owner cache " + addressOf(c) + ": dc.get(" + key + ") returned " + ice + "!";
            }
         }
      }
   }

   protected String safeType(Object o) {
      return DistributionTestHelper.safeType(o);
   }

   protected void assertIsInL1(Cache<?, ?> cache, Object key) {
      DistributionTestHelper.assertIsInL1(cache, key);
   }

   protected void assertIsNotInL1(Cache<?, ?> cache, Object key) {
      DistributionTestHelper.assertIsNotInL1(cache, key);
   }

   protected void assertIsInContainerImmortal(Cache<?, ?> cache, Object key) {
      DistributionTestHelper.assertIsInContainerImmortal(cache, key);
   }

   protected void assertIsInL1OrNull(Cache<?, ?> cache, Object key) {
      DistributionTestHelper.assertIsInL1OrNull(cache, key);
   }

   protected boolean isOwner(Cache<?, ?> c, Object key) {
      return DistributionTestHelper.isOwner(c, key);
   }

   protected boolean isFirstOwner(Cache<?, ?> c, Object key) {
      return DistributionTestHelper.isFirstOwner(c, key);
   }

   protected Cache<Object, String>[] getOwners(Object key) {
      return getOwners(key, 2);
   }

   protected Cache<Object, String>[] getOwners(Object key, int expectedNumberOwners) {
      Cache<Object, String>[] owners = new Cache[expectedNumberOwners];
      int i = 0;
      for (Cache<Object, String> c : caches) {
         if (isOwner(c, key)) owners[i++] = c;
      }
      for (Cache<?, ?> c : owners) assert c != null : "Have not found enough owners for key [" + key + "]";
      return owners;
   }

   protected Cache<Object, String>[] getNonOwnersExcludingSelf(Object key, Address self) {
      Cache<Object, String>[] nonOwners = getNonOwners(key);
      boolean selfInArray = false;
      for (Cache<?, ?> c : nonOwners) {
         if (addressOf(c).equals(self)) {
            selfInArray = true;
            break;
         }
      }

      if (selfInArray) {
         Cache<Object, String>[] nonOwnersExclSelf = new Cache[nonOwners.length - 1];
         int i = 0;
         for (Cache<Object, String> c : nonOwners) {
            if (!addressOf(c).equals(self)) nonOwnersExclSelf[i++] = c;
         }
         return nonOwnersExclSelf;
      } else {
         return nonOwners;
      }
   }

   protected Cache<Object, String>[] getNonOwners(Object key) {
      return getNonOwners(key, 2);
   }

   protected Cache<Object, String>[] getNonOwners(Object key, int expectedNumberNonOwners) {
      Cache<Object, String>[] nonOwners = new Cache[expectedNumberNonOwners];
      int i = 0;
      for (Cache<Object, String> c : caches) {
         if (!isOwner(c, key)) nonOwners[i++] = c;
      }
      return nonOwners;
   }

   protected List<Address> residentAddresses(Object key) {
      DistributionManager dm = c1.getAdvancedCache().getComponentRegistry().getComponent(DistributionManager.class);
      return dm.locate(key);
   }

   protected DistributionManager getDistributionManager(Cache<?, ?> c) {
      return c.getAdvancedCache().getComponentRegistry().getComponent(DistributionManager.class);
   }

   protected ConsistentHash getConsistentHash(Cache<?, ?> c) {
      return getDistributionManager(c).getConsistentHash();
   }

   /**
    * Blocks and waits for a replication event on async caches
    *
    * @param key     key that causes the replication.  Used to determine which caches to listen on.  If null, all caches
    *                are checked
    * @param command command to listen for
    * @param caches  on which this key should be invalidated
    */
   protected void asyncWait(Object key, Class<? extends VisitableCommand> command, Cache<?, ?>... caches) {
      // no op.
   }

   protected TransactionManager getTransactionManager(Cache<?, ?> cache) {
      return TestingUtil.getTransactionManager(cache);
   }
}
