package accumulo.ohc.tiered;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.spi.cache.BlockCache;
import org.apache.accumulo.core.spi.cache.BlockCacheManager;
import org.apache.accumulo.core.spi.cache.CacheType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EhCacheBackedBlockCacheManager extends BlockCacheManager {

  private static final Logger LOG = LoggerFactory.getLogger(EhCacheBackedBlockCacheManager.class);

  private Timer timer = null;

  @Override
  public void start(Configuration conf) {
    super.start(conf);

    for (CacheType type : CacheType.values()) {
      EhCacheBackedBlockCacheConfiguration cc =
          new EhCacheBackedBlockCacheConfiguration(conf, type);
      Long interval = cc.getLogInterval(TimeUnit.MILLISECONDS);
      if (interval != null) {
        if (timer == null) {
          timer = new Timer(true);
        }

        EhCacheBackedBlockCache ehbc = (EhCacheBackedBlockCache) getBlockCache(type);
        TimerTask task = new TimerTask() {
          @Override
          public void run() {
            ehbc.logStats();
          }
        };

        timer.scheduleAtFixedRate(task, interval, interval);
      }
    }
  }

  @Override
  protected BlockCache createCache(Configuration conf, CacheType type) {
    EhCacheBackedBlockCacheConfiguration cc = new EhCacheBackedBlockCacheConfiguration(conf, type);
    LOG.info("Creating {} cache with configuration {}", type, cc);
    return new EhCacheBackedBlockCache(cc, type);
  }

}
