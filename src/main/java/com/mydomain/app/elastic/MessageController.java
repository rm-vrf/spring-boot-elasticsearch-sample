package com.mydomain.app.elastic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {

	private static final Logger LOG = LoggerFactory.getLogger(MessageController.class);
	
	@RequestMapping(value = "/api/v1/message/{id}", method = RequestMethod.GET)
	public Message getMessage(String id) {
		return null;
	}
	
}
