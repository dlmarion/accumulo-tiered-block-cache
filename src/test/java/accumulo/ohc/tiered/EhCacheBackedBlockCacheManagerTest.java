package accumulo.ohc.tiered;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.apache.accumulo.core.spi.cache.BlockCache;
import org.apache.accumulo.core.spi.cache.CacheType;
import org.junit.jupiter.api.Test;

class EhCacheBackedBlockCacheManagerTest {

  @Test
  void createsEnabledCachesAndClosesThemOnStop() {
    TestConfiguration config = new TestConfiguration()
        .withCache(CacheType.DATA, 8192, Map.of("on-heap.size", "8192", "logInterval", "60"))
        .withCache(CacheType.INDEX, 0, Map.of("logInterval", "60"));
    EhCacheBackedBlockCacheManager manager = new EhCacheBackedBlockCacheManager();

    manager.start(config);
    BlockCache dataCache = manager.getBlockCache(CacheType.DATA);
    try {
      assertNotNull(dataCache);
      assertTrue(dataCache instanceof EhCacheBackedBlockCache);
      dataCache.cacheBlock("before-stop", new byte[] {1});
      assertNotNull(dataCache.getBlock("before-stop"));
    } finally {
      manager.stop();
    }

    assertNull(manager.getBlockCache(CacheType.DATA));
    assertThrows(IllegalStateException.class, () -> dataCache.getBlock("after-stop"));
  }
}
