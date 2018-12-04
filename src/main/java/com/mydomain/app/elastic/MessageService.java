package com.mydomain.app.elastic;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.elasticsearch.core.ElasticsearchTemplate;
import org.springframework.stereotype.Service;

@Service
public class MessageService {

	@Autowired
    private ElasticsearchTemplate elasticsearchTemplate;
	
	public void putMessage(Message message) {
		
		Map<String, Object> map = new HashMap<>();
		map.put("id", message.getId());
		map.put("time", message.getTime());
		map.put("sender", message.getSender());
		map.put("receiver", message.getReceiver());
		map.put("title", message.getTitle());
		map.put("body", message.getBody());
		
	}
	
	public Message getMessage(String id) {
		return null;
	}
	
	public Page<Message> searchMessage(String query, int page, int size) {
		return null;
	}
	
	public void deleteMessage(String id) {
		
	}
}
