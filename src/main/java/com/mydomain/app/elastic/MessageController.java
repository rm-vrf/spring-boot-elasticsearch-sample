package com.mydomain.app.elastic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {

	private static final Logger LOG = LoggerFactory.getLogger(MessageController.class);
	
	@Autowired
	private MessageService messageService;
	
	@RequestMapping(value = "/api/v1/message", method = RequestMethod.POST)
	public String putMessage(@RequestBody Message message) {
		String id = messageService.postMessage(message);
		LOG.debug("new message id: {}", id);
		return id;
	}
	
	@RequestMapping(value = "/api/v1/message/{id}", method = RequestMethod.GET)
	public Message getMessage(@PathVariable("id") String id) {
		return messageService.getMessage(id);
	}
	
	@RequestMapping(value = "/api/v1/message/{id}", method = RequestMethod.DELETE)
	public void deleteMessage(@PathVariable("id") String id) {
		messageService.deleteMessage(id);
	}
	
	@RequestMapping(value = "/api/v1/message/_search", method = RequestMethod.POST)
	public Page<Message> searchMessage(@RequestBody String query, 
			@RequestParam(name = "page", defaultValue = "0") int page, 
			@RequestParam(name = "size", defaultValue = "20") int size) {
		
		return messageService.searchMessage(query, page, size);
	}

}
