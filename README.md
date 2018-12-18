# spring-boot-elasticsearch-sample

Elasticsearch 封装了 Lucene 的功能，提供 Web API，并且实现了一个高可用集群。尽管 Elasticsearch 屏蔽了 Lecene 的大量技术细节，在决定使用 Elasticseach 之前，还是需要对分词、倒排索引这些概念有一个基本的了解，否则很难理解 `term` 查询和 `match` 查询有什么区别，在 `mapping` 上设置分词索引方式会带来什么样的影响。并且 Elasticsearch 是一个基于 Java 的系统，对这样一个系统进行维护和优化，还需要具备一些 JVM 的知识。

下面介绍一些 Elasticsearch 管理和使用的注意事项，对于一些开发事项给出了示例代码。示例代码使用 Spring Boot，在 `pom.xml` 里面添加了 Elasticsearch 数据源支持：

```xml
<dependency>
	<groupId>org.springframework.boot</groupId>
	<artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

在 `application.properties` 设置连接参数：

```properties
spring.data.elasticsearch.cluster-name=elasticsearch
spring.data.elasticsearch.cluster-nodes=localhost:9300
```

程序启动之后可以在 `/health` 端口看到 Elasticsearch 的连接状态：

```shell
$ curl http://localhost:8080/actuator/health
{
    "status": "UP",
    "details":
    {
        "elasticsearch":
        {
            "status": "UP",
            "details":
            {
                "clusterName": "elasticsearch",
                "numberOfNodes": 1,
                "numberOfDataNodes": 1,
                "activePrimaryShards": 26,
                "activeShards": 26,
                "relocatingShards": 0,
                "initializingShards": 0,
                "unassignedShards": 26
            }
        },
        "diskSpace":
        {
            "status": "UP",
            "details":
            {
                "total": 250790436864,
                "free": 45851045888,
                "threshold": 10485760
            }
        }
    }
}
```

Elasticsearch Starter 为我们提供了依赖项、自动配置、Bean 注入、运行指标、健康检查。所以我们使用一个组件的时候，只要有可能，就应该使用 Starter.

## 1 集群管理

### 1.1 足够的内存

Elasticsearch 运行要占用大量内存，要为进程分配足够的内存。引用 `elasticsearch.yml` 里面的一段注释：

```shell
# Make sure that the heap size is set to about half the memory available
# on the system and that the owner of the process is allowed to use this
# limit.
#
# Elasticsearch performs poorly when the system is swapping the memory.
```

提高堆内存参数，生产环境 8G 起：

```shell
ES_JAVA_OPTS="-Xms8g -Xmx8g" bin/elasticsearch
```

并且开启内存锁定，避免 `swap` 影响性能：

```shell
bootstrap.memory_lock: true
```

> 开启内存锁定不仅要打开这个开关，还需要打开操作系统的开关。

Elasticsearch 运行要注意观察日志，发现长 GC 要及时应对。

### 1.2 专用主节点

Elasticsearch 采用广播发现机制自动组成集群，在集群中选举主节点，在小规模场景下这样非常方便。为了提高大规模集群的稳定性，要采用专用的主节点。可以选择一些低配节点作为主节点，不存储数据，这样设置：

```shell
node.master: true
node.data: false
```

另一部分高配置机器用来做存储和查询，不能成为主节点。这样设置：

```shell
node.master: false
node.data: true
```

主节点的数量设置为奇数，并且选票数要大于主节点总数的一半。例如主节点的数量是 `3`，最少主节点数要设置为 `2`，这样可以避免发生危险的脑裂现象：

```shell
discovery.zen.minimum_master_nodes: 2
```

在网络条件比较差的情况下，需要禁用广播功能，强行指定主节点的位置，提高集群稳定性。在高延时网络中有必要延长超时时间：

```shell
discovery.zen.ping.multicast.enabled: false
discovery.zen.ping.unicast.hosts: ["172.192.0.11", "172.192.0.12", "172.192.0.13"]
discovery.zen.ping.timeout: 10s
```

> Elasticsearch 默认的集群名称是 `elasticsearch`，在同一局域网里，同名的节点会自动组成一个集群，互相平衡对方的数据。有时候不同团队创建的 Elasticsearch 节点就会这样无意中组成集群，带来数据混合的问题。还有一些情况下网络中的一些协议被禁止，Elasticsearch 集群无法互相发现，成为孤立的节点。所以在启动新的 Elasticsearch 节点之后，一定要确认集群的状态是否和预想一致。

### 1.3 禁用脚本

脚本可以增强数据更新和查询的功能，但是也会带来的性能问题和安全性隐患。比如下面这个脚本可以卡死一个操作线程：

```shell
{
    "script_fields": {
        "test1": {
            "lang": "groovy",
            "script": "while (true) {print 'Hello world'}"
        }
    }
}
```

当这样的操作继续下去，直到所有的操作线程都卡死之后，Elasticsearch 集群就无法响应任何请求了。脚本能够做更加危险的事情，比如调用 `wget` 指令下载一个木马文件，然后运行这个文件，就可以拥有服务器的控制权。最好的办法是把脚本功能禁止掉：

```shell
script.disable_dynamic: true
```

如果不得不使用的话，一定要注意以下几点：

- 控制 Elasticsearch 服务的开放范围；
- 并且要控制主机的外网通信，防止信息泄露；
- 不能用 `root` 账号运行 Elasticsearch，降低恶意脚本的影响。

## 2 数据管理

### 2.1 防止 Mapping 膨胀

Elasticsearch 可以自动创建 Mapping。当我们向 Elasticsearch 里插入一个新的数据类型，或者在一个数据类型上插入一个新的字段，Elasticsearch 可以自动更新 mapping，识别字段的类型，为这个字段创建分词索引。这个功能给人一个错觉，似乎可以像使用 HBase 一样使用 Elasticsearch，把 `value` 当做列。比如我看到过有的开发者把用户名当做 `key`，把阅读时间作为 `value`，这样就可以记录用户阅读文档的时间，查询还很方便。

但是 Elasticsearch 管理列的代价比 HBase 要大得多。首先 Mapping 创建和更新需要在整个集群上同步，要锁定所有的分区，整个索引的操作都要停下来，等 Mapping 更新结束才能继续进行，经常更新 Mapping 会影响集群的性能；然后 Elasticsearch 要管理列类型、存储方式、分词和索引，消耗更多的内存。

所以要保持 Mapping 的稳定，不能频繁更新。正确的对待 `key` 和 `value`，如果要记录用户阅读文档的时间，使用一个 `inner object` 更合适。

### 2.2 使用 Template

Elasticsearch 可以自动识别数据类型，创建索引。这个功能非常方便，但是有些情况下也会带来麻烦。举个例子，向 Elasticsearch 插入一条数据：

```shell
{
    "action": "Some action",
    "payload": "2016-01-20"
}
```

Elasticsearch 会把 `payload` 属性识别成 `date` 类型，创建 Mapping。接下来再插入第二个数据：

```shell
{
    "action": "Some action 1",
    "payload": "USER_LOCKED"
}
```

这时候会发生错误，因为 `payload` 属性已经被识别成 `date` 类型，无法保存第二个数据。自动识别数据类型无法避免这一类问题，并且对 `inner object` 和 `nested object` 也是无法识别的。解决的办法是手工创建 Mapping，或者使用 Template（模板）。

示例代码在初始化时创建了模板：

```java
XContentBuilder json = XContentFactory.jsonBuilder().startObject()
    .startObject(Message.TYPE_NAME)
    .startObject("properties")
    .startObject("body").field("type", "string").endObject()
    .startObject("id").field("type", "string").endObject()
    .startObject("receiver").field("type", "string").endObject()
    .startObject("sender").field("type", "string").endObject()
    .startObject("time").field("format", "epoch_millis")
    .field("type", "date").endObject()
    .startObject("title").field("type", "string").endObject()
    .endObject().endObject().endObject();

PutIndexTemplateRequestBuilder req = elasticsearchTemplate.getClient()
    .admin().indices()
    .preparePutTemplate(Message.ALIAS_NAME)
    .setTemplate(Message.ALIAS_NAME + "-*")
    .setSettings(Settings.builder().put("index.number_of_shards", "8")
    .put("index.number_of_replicas", "1").build())
    .addMapping(Message.TYPE_NAME, json)
    .addAlias(new Alias(Message.ALIAS_NAME));

PutIndexTemplateResponse resp = req.execute().get();
```

数据插入时如果匹配到 `message-*` 索引名称，会按照模板的要求创建 Index、Mapping、别名。以上代码等同于下面这个指令：

```shell
$ curl -XPUT http://localhost:9200/_template/message -d '
{
    "template": "message-*",
    "settings": {
        "index": {
            "number_of_shards": "8",
            "number_of_replicas": "1"
        }
    }, 
    "mappings": {
        "data": {
            "properties": {
                "body": {
                    "type": "string"
                },
                "id": {
                    "type": "string"
                },
                "receiver": {
                    "type": "string"
                },
                "sender": {
                    "type": "string"
                },
                "time": {
                    "format": "epoch_millis",
                    "type": "date"
                },
                "title": {
                    "type": "string"
                }
            }
        }
    },
    "aliases": {
        "message": {}
    }
}'
```

### 2.3 合理设置分片数

要合理设置分片数和副本数，既要支持足够的并发查询，又要避免分片过多。要预估数据量，保持每个分片的数据量在 200MB 左右/* 这个数值需要商榷 */。过多分片会占用大量的内存，即使是空分片也会占用一定的内存；太少的分片又会降低查询性能，并且大分片一旦发生平衡会占用大量网络带宽，带来长时间的延时和停顿。

### 2.4 大索引要分割

随时间增长的数据要分索引存储，这是使用 Elasticsearch 的正确姿势，要把索引体积控制在合理大小。可以使用 Template 预先设置索引格式。示例代码根据日期分割索引：

```java
String date = DATE_FORMAT.get().format(message.getTime());
String indexName = Message.ALIAS_NAME + "-" + date;
message.setId(UUID.randomUUID().toString() + "-" + date);

IndexQuery indexQuery = new IndexQueryBuilder()
		.withIndexName(indexName)
		.withType(Message.TYPE_NAME)
		.withId(message.getId())
		.withObject(message).build();

elasticsearchTemplate.index(indexQuery);
```

示例代码将日期作为索引名称后缀，每天的数据保存在一个索引中。实际要合理估算数据量，按日、周、月分割。程序启动之后可以尝试插入数据：

```shell
curl -XPOST http://localhost:8080/api/v1/message -H 'Content-Type:application/json' -d '{
    "time": 1543984834000,
    "sender": "user962206",
    "receiver": "dans00",
    "title": "What is the unix time stamp",
    "body": "The unix time stamp is a way to track time as a running total of seconds. 
    This count starts at the Unix Epoch on January 1st, 1970 at UTC"
}'
```

操作成功后可以看到索引已经按照 Template 定义的格式被创建出来：

```shell
$ curl http://localhost:9200/_cat/aliases
message message-20181205 - - -
```

日期作为后缀添加在 `_id` 属性上，获取数据时可以直接在对应的索引调用 `get` 接口，避免不必要的查询操作。`get` 操作比 `search` 操作消耗的资源少的多，并且可以避免索引延时的问题：

```java
String date = id.substring(id.length() - "yyyyMMdd".length());
String indexName = Message.ALIAS_NAME + "-" + date;

SearchQuery query = new NativeSearchQueryBuilder()
		.withIndices(indexName)
		.withTypes(Message.TYPE_NAME)
		.withIds(Arrays.asList(id)).build();

List<Message> list = elasticsearchTemplate.multiGet(query, Message.class);
```

### 2.5 使用批量操作

大量数据插入要使用 `bulk` 方式批量操作，合理控制批量大小。这样可以大幅度提高索引速度。

### 2.6 多语种存储到多个字段

不同语言的处理策略是有区别的，比如分词算法、词干提取方法，停止词、高频词也不一样，由于不同语言的高频词干扰，也会影响文档的 TF-IDF 分数，影响文档排序。如果需要处理多种语言，不能把不同语种保存在同一个字段里，可以保存在不同的索引中，也可以保存在同一索引的多个字段中，为每一个字段设置不同的分词策略：

```shell
curl -XPUT http://localhost:9200/_template/message -d '
{
    "template": "message-*",
    "mappings": {
        "data": {
            "properties": {
                "title_zh": {
                    "type": "string",
                    "analyzer": "ik_smart"
                },
                "title_en": {
                    "type": "string",
                    "analyzer": "english"
                },
                "title_es": {
                    "type": "string",
                    "analyzer": "spanish"
                },
                "id": {
                    "type": "string"
                }
            }
        }
    }
}'
```

## 3 数据查询

### 3.1 避免大查询

Elasticsearch 查询接口返回分页数据，默认页长是 `20`. 有一些开发者想要在一次查询中得到所有的结果，于是会设置一个非常大的页长，甚至会设置到 `Integer.MAX_VALUE`, 也就是 `2147483647`. 这样做的后果就是：Elasticsearch 会在堆内存中分配大量空间，创建一个超大的数据集合。代价就是长时间的等待，如果堆内存耗尽的话，就永远等不到结果。

避免大查询的办法是采用分页方式，每次查出一页数据，多次查询把数据全部查出。最简单的办法是深分页，设置查询的 `from` 参数，在数据量不是特别大的情况下，这样是可以的。如果数据量特别大，会造成深分页，影响查询性能；

另一个办法是游标查询，在调用 `search` 接口时设置参数 `scroll` 的值，设置游标过期时间。查询结果会包含一个 `_scroll_id`, 能传递字段 `_scroll_id` 到 `_search/scroll` 查询接口获取下一批结果；

还有一个办法是使用某个递增字段排序，每次记录最后一个记录的递增字段的值。根据这个递增字段逐步跳跃得到所有的数据，当然这个递增字段是不能有重复值的。

> Elasticsearch 并不适合用来做全量数据分析。从库中读出所有的数据，或者大比例的数据，会在 Elasticsearch 中产生大量的随机读，消耗大量的时间。如果这样的操作经常发生，要考虑使用其他形式的存储。

### 3.2 注意索引延时

为了提高吞吐量，写入 Elasticsearch 的数据不会立刻写进索引，而是先写 `translog` 文件，然后立刻写入 `index buffer`，这个时刻数据是不能被检索到的。如果这时发生断电或崩溃，`index buffer` 里面的数据会丢失。重新启动之后，Elasticsearch 会从 `translog` 恢复最近写入的数据。Elasticsearch 在空闲的时候从 `index buffer` 中读出数据刷盘存储，直到这个时候数据才能被查询到。默认的刷盘时间是 `1` 秒，也就是说，保存到 Elasticsearch 的数据有可能几百毫秒之后才能被查询到。

使用 Elasticsearch 要注意这个现象，设计的时候要避免出现索引数据需要立刻被查询到的情况。比如在页面中输入数据，按下提交按钮之后保存数据，跳转到列表页面，在列表页面按照查询条件需要查到刚才输入的数据。

### 3.3 避免深度聚合查询

- 聚合查询要预估数据量，过深或过宽的聚合查询会占用大量内存，在多个上字段做聚合查询结果数量是这些字段的 term 数量相乘，有可能造成 elasticsearch 停顿甚至掉线。要预估内存占用量和操作的时间；

### 3.4 控制查询线程数

- 长延时的查询会占用大量的系统资源。如果长延时查询过多，要控制 search 线程的数量，避免查询压力过大造成性能雪崩；

### 3.5 控制通配符查询

- 通配符查询要注意词长度，避免查询膨胀度过大。elasticsearch 首先会按照通配符查找词库，把通配符条件改写成 `bool` 条件，组合成多个 `should` 再进行查询。如果通配词过短，会匹配出大量的查询词，造成查询膨胀度过大。可以控制通配符的长度必须在 n 个字母以上，或者设置 `top_terms_N` 改写条件，只保留最符合的 n 个词。

```shell
对全文检索、分词、文档得分算法有一些知识还是必要的

“This is a sentence a b c”
“This is a sentence a b c”
“This is a sentence a b c d”

“a” – 6
“This” – 3
“is” – 3
“sentence” – 3
“b” – 3
“c” – 3
“d” – 1

“This is a sentence a b c” – 2
“This is a sentence a b c d” – 1
```

```
cluster.name: elasticsearch_production
gateway.recover_after_nodes: 10
gateway.expected_nodes: 10
gateway.recover_after_time: 5m
minimum_master_nodes
discovery.zen.minimum_master_nodes: 2

(N/2) + 1

curl -XPOST localhost:8080/api/v1/message/_search -H 'Content-Type:application/json' -d 'unix'
```


