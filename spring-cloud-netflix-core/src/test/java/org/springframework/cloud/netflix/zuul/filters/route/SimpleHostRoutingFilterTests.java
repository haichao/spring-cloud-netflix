/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.zuul.filters.route;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.zuul.filters.ProxyRequestHelper;
import org.springframework.cloud.netflix.zuul.filters.ZuulProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.boot.test.util.EnvironmentTestUtils.addEnvironment;
import static org.springframework.util.StreamUtils.copyToByteArray;
import static org.springframework.util.StreamUtils.copyToString;

/**
 * @author Andreas Kluth
 * @author Spencer Gibb
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = SampleApplication.class,
		webEnvironment = RANDOM_PORT,
		properties = "server.contextPath: /app")
@DirtiesContext
public class SimpleHostRoutingFilterTests {

	private AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();

	@LocalServerPort
	private int port;

	@After
	public void clear() {
		if (this.context != null) {
			this.context.close();
		}
	}

	@Test
	public void connectionPropertiesAreApplied() {
		addEnvironment(this.context, "zuul.host.maxTotalConnections=100", "zuul.host.maxPerRouteConnections=10");
		setupContext();
		PoolingHttpClientConnectionManager connMgr = getFilter().newConnectionManager();
		assertEquals(100, connMgr.getMaxTotal());
		assertEquals(10, connMgr.getDefaultMaxPerRoute());
	}

	@Test
	public void validateSslHostnamesByDefault() {
		setupContext();
		assertTrue("Hostname verification should be enabled by default",
				getFilter().isSslHostnameValidationEnabled());
	}

	@Test
	public void validationOfSslHostnamesCanBeDisabledViaProperty() {
		addEnvironment(this.context, "zuul.sslHostnameValidationEnabled=false");
		setupContext();
		assertFalse("Hostname verification should be disabled via property",
				getFilter().isSslHostnameValidationEnabled());
	}

	@Test
	public void defaultPropertiesAreApplied() {
		setupContext();
		PoolingHttpClientConnectionManager connMgr = getFilter().newConnectionManager();

		assertEquals(200, connMgr.getMaxTotal());
		assertEquals(20, connMgr.getDefaultMaxPerRoute());
	}

	@Test
	public void deleteRequestBuiltWithBody() {
		setupContext();
		InputStreamEntity inputStreamEntity = new InputStreamEntity(new ByteArrayInputStream(new byte[]{1}));
		HttpRequest httpRequest = getFilter().buildHttpRequest("DELETE", "uri", inputStreamEntity,
				new LinkedMultiValueMap<String, String>(), new LinkedMultiValueMap<String, String>(), new MockHttpServletRequest());

		assertTrue(httpRequest instanceof HttpEntityEnclosingRequest);
		HttpEntityEnclosingRequest httpEntityEnclosingRequest = (HttpEntityEnclosingRequest) httpRequest;
		assertTrue(httpEntityEnclosingRequest.getEntity() != null);
	}

	@Test
	public void httpClientDoesNotDecompressEncodedData() throws Exception {
		setupContext();
		InputStreamEntity inputStreamEntity = new InputStreamEntity(new ByteArrayInputStream(new byte[]{1}));
		HttpRequest httpRequest = getFilter().buildHttpRequest("GET", "/app/compressed/get/1", inputStreamEntity,
				new LinkedMultiValueMap<String, String>(), new LinkedMultiValueMap<String, String>(), new MockHttpServletRequest());

		CloseableHttpResponse response = getFilter().newClient().execute(new HttpHost("localhost", this.port), httpRequest);
		assertEquals(200, response.getStatusLine().getStatusCode());
		byte[] responseBytes = copyToByteArray(response.getEntity().getContent());
		assertTrue(Arrays.equals(GZIPCompression.compress("Get 1"), responseBytes));
	}

	@Test
	public void httpClientPreservesUnencodedData() throws Exception {
		setupContext();
		InputStreamEntity inputStreamEntity = new InputStreamEntity(new ByteArrayInputStream(new byte[]{1}));
		HttpRequest httpRequest = getFilter().buildHttpRequest("GET", "/app/get/1", inputStreamEntity,
				new LinkedMultiValueMap<String, String>(), new LinkedMultiValueMap<String, String>(), new MockHttpServletRequest());

		CloseableHttpResponse response = getFilter().newClient().execute(new HttpHost("localhost", this.port), httpRequest);
		assertEquals(200, response.getStatusLine().getStatusCode());
		String responseString = copyToString(response.getEntity().getContent(), Charset.forName("UTF-8"));
		assertTrue("Get 1".equals(responseString));
	}

	private void setupContext() {
		this.context.register(PropertyPlaceholderAutoConfiguration.class,
				TestConfiguration.class);
		this.context.refresh();
	}

	private SimpleHostRoutingFilter getFilter() {
		return this.context.getBean(SimpleHostRoutingFilter.class);
	}

	@Configuration
	@EnableConfigurationProperties(ZuulProperties.class)
	protected static class TestConfiguration {
		@Bean
		SimpleHostRoutingFilter simpleHostRoutingFilter(ZuulProperties zuulProperties) {
			return new SimpleHostRoutingFilter(new ProxyRequestHelper(), zuulProperties);
		}
	}
}

@Configuration
@EnableAutoConfiguration
@RestController
class SampleApplication {

	public static void main(String[] args) {
		SpringApplication.run(SampleApplication.class, args);
	}

	@RequestMapping(value = "/compressed/get/{id}", method = RequestMethod.GET)
	public byte[] getCompressed(@PathVariable String id, HttpServletResponse response) throws IOException {
		response.setHeader("content-encoding", "gzip");
		return GZIPCompression.compress("Get " + id);
	}

	@RequestMapping(value = "/get/{id}", method = RequestMethod.GET)
	public String getString(@PathVariable String id, HttpServletResponse response) throws IOException {
		return "Get " + id;
	}
}

class GZIPCompression {

	public static byte[] compress(final String str) throws IOException {
		if ((str == null) || (str.length() == 0)) {
			return null;
		}
		ByteArrayOutputStream obj = new ByteArrayOutputStream();
		GZIPOutputStream gzip = new GZIPOutputStream(obj);
		gzip.write(str.getBytes("UTF-8"));
		gzip.close();
		return obj.toByteArray();
	}
}
