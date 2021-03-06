package org.infinispan.container.offheap;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.io.IOException;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.infinispan.Cache;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.marshall.WrappedByteArray;
import org.infinispan.commons.marshall.WrappedBytes;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.StorageType;
import org.infinispan.container.DataContainer;
import org.infinispan.filter.KeyFilter;
import org.infinispan.test.TestingUtil;
import org.infinispan.util.ControlledTimeService;
import org.infinispan.util.TimeService;
import org.testng.annotations.Test;

/**
 */
@Test(groups = "functional", testName = "container.offheap.OffHeapSingleNodeTest")
public class OffHeapSingleNodeTest extends OffHeapMultiNodeTest {

   protected ControlledTimeService timeService;

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder dcc = getDefaultClusteredCacheConfig(CacheMode.LOCAL, false);
      dcc.memory().storageType(StorageType.OFF_HEAP);
      // Only start up the 1 cache
      addClusterEnabledCacheManager(dcc);

      configureTimeService();
   }

   protected void configureTimeService() {
      timeService = new ControlledTimeService(14151);
      TestingUtil.replaceComponent(cacheManagers.get(0), TimeService.class, timeService, true);
   }

   public void testLockOnExecuteTask() throws InterruptedException, TimeoutException, BrokenBarrierException,
         ExecutionException, IOException {
      Cache<byte[], byte[]> cache = cache(0);
      Marshaller marshaller = cache.getAdvancedCache().getComponentRegistry().getCacheMarshaller();
      byte[] key = randomBytes(KEY_SIZE);
      WrappedBytes keyWB = new WrappedByteArray(marshaller.objectToByteBuffer(key));
      byte[] value = randomBytes(VALUE_SIZE);
      WrappedBytes valueWB = new WrappedByteArray(marshaller.objectToByteBuffer(value));
      cache.put(key, value);

      CyclicBarrier barrier = new CyclicBarrier(2);

      Future<Void> future = fork(() -> {
         // There is only 1 key so it should be the same lock
         castDC(cache.getAdvancedCache().getDataContainer()).executeTask(KeyFilter.ACCEPT_ALL_FILTER,
               (k, ice) -> {
                  try {
                     barrier.await(10, TimeUnit.SECONDS);
                     barrier.await(10, TimeUnit.SECONDS);
                     assertTrue(keyWB.equalsWrappedBytes(k));
                     assertTrue(keyWB.equalsWrappedBytes(ice.getKey()));
                     assertTrue(valueWB.equalsWrappedBytes(ice.getValue()));
                  } catch (InterruptedException | BrokenBarrierException | TimeoutException e) {
                     throw new RuntimeException(e);
                  }
               });
      });

      barrier.await(10, TimeUnit.SECONDS);

      Future<byte[]> putFuture = fork(() -> cache.put(key, randomBytes(VALUE_SIZE)));
      try {
         putFuture.get(1, TimeUnit.SECONDS);
         fail("Should have blocked");
      } catch (TimeoutException e) {
         // Should time out
      }
      barrier.await(10, TimeUnit.SECONDS);
      future.get(10, TimeUnit.SECONDS);
      assertEquals(value, putFuture.get(10, TimeUnit.SECONDS));
   }

   public void testLotsOfWrites() {
      Cache<String, String> cache = cache(0);

      for (int i = 0; i < 5_000; ++i) {
         cache.put("key" + i, "value" + i);
      }
   }

   public void testExpiredEntryCompute() throws IOException, InterruptedException {
      Cache<Object, Object> cache = cache(0);

      String key = "key";

      cache.put(key, "value", 10, TimeUnit.MILLISECONDS);

      timeService.advance(20);

      Marshaller marshaller = cache.getAdvancedCache().getComponentRegistry().getCacheMarshaller();

      WrappedBytes keyWB = new WrappedByteArray(marshaller.objectToByteBuffer(key));

      AtomicBoolean invoked = new AtomicBoolean(false);
      DataContainer container = cache.getAdvancedCache().getDataContainer();
      container.compute(keyWB, (k, e, f) -> {
         invoked.set(true);
         // Just leave it in there
         return e;
      });

      // Should be expired from cache point of view
      assertNull(cache.get(key));

      // Should be expired from data container get
      assertNull(container.get(keyWB));

      // Should be viewable as peek as well
      assertNotNull(container.peek(keyWB));
   }
}
