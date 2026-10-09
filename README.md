# Accumulo Tiered Block Cache

This project is similar to the [accumulo-ohc](https://github.com/keith-turner/accumulo-ohc) and 
[accumulo-shared-offheap-cache](https://github.com/dlmarion/accumulo-shared-offheap-cache), but it contains an
additional disk layer and is not clustered.

This BlockCacheManager implementation uses [Ehcache](https://www.ehcache.org/) as the library supports on-heap,
off-heap, disk, and clustering configurations. This implementation does not currently expose the clustering
feature.

More information about the Ehcache tiering options can be found at https://www.ehcache.org/documentation/3.11/tiering.html.
As mentioned, this implementation does not currently configure the clustering feature, but the following configurations
should be valid:

  * Heap
  * OffHeap
  * Disk
  * Heap + OffHeap
  * Heap + Disk
  * Heap + OffHeap + Disk

# Building
```
mvn clean package
```

# Installation

After building, copy the jar files from the resulting tarball to a directory on Accumulo's classpath.

# Configuration

Once the jar files are in the correct location, the following values will need to be put into the `accumulo.properties` file.

```
general.block.cache.manager.class=accumulo.ohc.tiered.EhCacheBackedBlockCacheManager
tserver.cache.config.tiered.default.logInterval=30
tserver.cache.config.tiered.data.on-heap.size=1048576
tserver.cache.config.tiered.data.off-heap.size=10485760
tserver.cache.config.tiered.data.disk.size=104857600
tserver.cache.config.tiered.data.disk.dir=/path/to/dir/cache_data
tserver.cache.config.tiered.data.disk.segments=16
tserver.cache.config.tiered.data.disk.threads=1
tserver.cache.config.tiered.index.on-heap.size=1048576
tserver.cache.config.tiered.index.off-heap.size=10485760
tserver.cache.config.tiered.index.disk.size=104857600
tserver.cache.config.tiered.index.disk.dir=/path/to/dir/cache_index
tserver.cache.config.tiered.index.disk.segments=16
tserver.cache.config.tiered.index.disk.threads=1
tserver.cache.config.tiered.summary.on-heap.size=1048576
tserver.cache.config.tiered.summary.off-heap.size=10485760
tserver.cache.config.tiered.summary.disk.size=104857600
tserver.cache.config.tiered.summary.disk.dir=/path/to/dir/cache_summary
tserver.cache.config.tiered.summary.disk.segments=16
tserver.cache.config.tiered.summary.disk.threads=1

```

Additionally, you will likely need to add `"--add-opens=java.base/java.lang=ALL-UNNAMED"` to the `JAVA_OPTS` variable in accumulo-env.sh.
