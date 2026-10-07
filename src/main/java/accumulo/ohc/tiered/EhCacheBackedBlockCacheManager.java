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
  public void stop() {
    if (timer != null) {
      timer.cancel();
      timer = null;
    }
    try {
      for (CacheType type : CacheType.values()) {
        BlockCache cache = getBlockCache(type);
        if (cache instanceof EhCacheBackedBlockCache ehbc) {
          ehbc.close();
        }
      }
    } finally {
      super.stop();
    }
  }

  @Override
  public void start(Configuration conf) {
    super.start(conf);

    for (CacheType type : CacheType.values()) {
      EhCacheBackedBlockCacheConfiguration cc =
          new EhCacheBackedBlockCacheConfiguration(conf, type);
      Long interval = cc.getLogInterval(TimeUnit.MILLISECONDS);
      BlockCache blockCache = getBlockCache(type);
      if (interval != null && blockCache instanceof EhCacheBackedBlockCache ehbc) {
        if (timer == null) {
          timer = new Timer(true);
        }

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
