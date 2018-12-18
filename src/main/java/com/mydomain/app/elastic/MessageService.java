package com.mydomain.app.elastic;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import javax.annotation.PostConstruct;

import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequestBuilder;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.data.elasticsearch.core.query.SearchQuery;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

	private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);
	private static final ThreadLocal<DateFormat> DATE_FORMAT = new ThreadLocal<DateFormat>() {
		protected DateFormat initialValue() {
			return new SimpleDateFormat("yyyyMMdd");
		}
	};

	@Autowired
	private ElasticsearchTemplate elasticsearchTemplate;

	@PostConstruct
	public void init() throws IOException, InterruptedException, ExecutionException {
		XContentBuilder json = XContentFactory.jsonBuilder().startObject().startObject(Message.TYPE_NAME)
				.startObject("properties")
				.startObject("body").field("type", "string").endObject()
				.startObject("id").field("type", "string").endObject()
				.startObject("receiver").field("type", "string").endObject()
				.startObject("sender").field("type", "string").endObject()
				.startObject("time").field("format", "epoch_millis").field("type", "date").endObject()
				.startObject("title").field("type", "string").endObject()
				.endObject().endObject().endObject();

		PutIndexTemplateRequestBuilder req = elasticsearchTemplate.getClient().admin().indices()
				.preparePutTemplate(Message.ALIAS_NAME)
				.setTemplate(Message.ALIAS_NAME + "-*")
				.setSettings(Settings.builder().put("index.number_of_shards", "8").put("index.number_of_replicas", "1").build())
				.addMapping(Message.TYPE_NAME, json)
				.addAlias(new Alias(Message.ALIAS_NAME));

		PutIndexTemplateResponse resp = req.execute().get();
		LOG.info("create template: {}", resp.isAcknowledged());
	}

	public String postMessage(Message message) {

		String date = DATE_FORMAT.get().format(message.getTime());
		String indexName = Message.ALIAS_NAME + "-" + date;
		message.setId(UUID.randomUUID().toString() + "-" + date);

		IndexQuery indexQuery = new IndexQueryBuilder()
				.withIndexName(indexName)
				.withType(Message.TYPE_NAME)
				.withId(message.getId())
				.withObject(message).build();

		return elasticsearchTemplate.index(indexQuery);
	}

	public Message getMessage(String id) {
		String date = id.substring(id.length() - "yyyyMMdd".length());
		String indexName = Message.ALIAS_NAME + "-" + date;

		SearchQuery query = new NativeSearchQueryBuilder()
				.withIndices(indexName)
				.withTypes(Message.TYPE_NAME)
				.withIds(Arrays.asList(id)).build();

		List<Message> list = elasticsearchTemplate.multiGet(query, Message.class);

		return list.size() > 0 ? list.get(0) : null;
	}

	public Page<Message> searchMessage(String query, int page, int size) {
		LOG.info("query string: {}", query);
		
		SearchQuery searchQuery = new NativeSearchQueryBuilder()
				.withIndices(Message.ALIAS_NAME)
				.withTypes(Message.TYPE_NAME)
				.withQuery(QueryBuilders.queryStringQuery(query))
				.withPageable(PageRequest.of(page, size, Sort.by(Sort.Order.desc("time"))))
				.build();
		
		return elasticsearchTemplate.queryForPage(searchQuery, Message.class);
	}

	public void deleteMessage(String id) {
		String date = id.substring(id.length() - "yyyyMMdd".length());
		String indexName = Message.ALIAS_NAME + "-" + date;
		
		elasticsearchTemplate.delete(indexName, Message.TYPE_NAME, id);
	}

}
