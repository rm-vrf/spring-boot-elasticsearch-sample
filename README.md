# spring-boot-elasticsearch-sample

Elasticsearch 使用原则，13 条军规：

## 集群管理

- elasticsearch 运行要占用大量内存，要为进程分配足够的内存，设置 `Xmx` 和 `Xms` 参数。要开启内存锁定，避免 swap 影响性能。要注意观察日志，发现长 gc 要及时应对；
- 即使数据量不大也要部署成集群模式，提高数据可用性。大规模集群（8+ 节点）要安排低配机器设置成专用的主节点，一般设置奇数个。必要的情况下禁用自动发现，手工设置主节点位置。设置最小主节点数，避免发生脑裂现象；
- 禁用 groovy 脚本，性能和安全性隐患很大；

## 数据管理

- 正确处理 key 和 value，避免 mapping 爆炸；mapping 更新需要在集群上同步，并且会锁定数据分片，频繁更新 mapping 会影响性能；
- elasticsearch 会自动识别数据格式，创建 mapping。但是尽量不要让他自动识别，自动识别数据类型、`inner object`、`nested object` 很容易发生错误。要用 template 预设数据格式，包括字段名称、字段类型、索引方式、副本数、分片数、别名；
- 要合理设置分片数和副本数，既要支持并发查询，又要避免分片过多。要预估数据量，计算合理的分片数，保持每个分片的数据量在 200MB 左右。过多的分片会占用大量的内存，即使是空分片也会占用一定的内存。太少的分片又会降低查询性能，并且大分片一旦发生移动会占用大量网络资源，速度也非常慢；
- 随时间增长的数据要按时间分索引（根据数据量选择分月、分日），每个索引分片大小要控制在 200MB 左右。可以使用 template 预先设置数据格式；查询时采用通配符做索引名称，或者使用别名查询；
- 大量数据插入要使用 `bulk` 方式批量插入，合理控制 batch size；
- 不同的语种文字不要保存在同一个字段里，否则影响 TF-IDF 分数，并且分词规则也不好做。可以使用不同索引存储不同的语种，或者使用同一索引内的多个字段存储多个语种。不能使用同一索引内的不同类型存储不同的语种；

## 数据查询

- 避免一次从 elasticsearch 中查出过多的数据，要用分页方式多次查出。要避免深分页，可以使用递增字段排序查询，或者调用 `scroll` 接口；从库中读出大量数据做全量分析，这样的需求使用 elasticsearch 是不合适的，如果经常存在这种操作，要考虑采用其他存储；
- 聚合查询要预估数据量，过深或过宽的聚合查询会占用大量内存，在多个上字段做聚合查询结果数量是这些字段的 term 数量相乘，有可能造成 elasticsearch 停顿甚至掉线。要预估内存占用量和操作的时间；
- 长延时的查询会占用大量的系统资源。如果长延时查询过多，要控制 search 线程的数量，避免查询压力过大造成性能雪崩；
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

```

 
```
{
	“action”: “Some action”,
	“payload”: “2016-01-20”
}

{
	“action”: “Some action 1”,
	“payload”: “USER_LOCKED”
}
```



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

curl -XPOST http://localhost:8080/api/v1/message -H 'Content-Type:application/json' -d '{
    "time": 1543984834000,
    "sender": "user962206",
    "receiver": "dans00",
    "title": "What is the unix time stamp",
    "body": "The unix time stamp is a way to track time as a running total of seconds. This count starts at the Unix Epoch on January 1st, 1970 at UTC"
}'

curl -XPOST localhost:8080/api/v1/message/_search -H 'Content-Type:application/json' -d 'unix'

```
