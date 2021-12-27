package io.antmedia.rest.servlet;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.util.EntityUtils;
import org.mitre.dsmiley.httpproxy.ProxyServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;

public class EndpointProxy extends ProxyServlet {

    private static HttpClient proxyClient;

    protected static Logger log = LoggerFactory.getLogger(EndpointProxy.class);


    /**
     *
     * @param servletRequest
     * @param servletResponse
     * @throws ServletException
     * @throws IOException
     *
     * Creates the exact same request with same properties for distributing inside cluster.
     */
    @Override
    protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
            throws ServletException, IOException {
        if (servletRequest.getAttribute(ATTR_TARGET_URI) == null) {
            servletRequest.setAttribute(ATTR_TARGET_URI, this.targetUri);
        }

        if (servletRequest.getAttribute(ATTR_TARGET_HOST) == null) {
            servletRequest.setAttribute(ATTR_TARGET_HOST, this.targetHost);
        }

        String method = servletRequest.getMethod();
        String proxyRequestUri = this.rewriteUrlFromRequest(servletRequest);
        Object proxyRequest;

        if (servletRequest.getHeader("Content-Length") == null && servletRequest.getHeader("Transfer-Encoding") == null) {
            proxyRequest = new BasicHttpRequest(method, proxyRequestUri);
        } else {
            proxyRequest = this.newProxyRequestWithEntity(method, proxyRequestUri, servletRequest);
        }

        this.copyRequestHeaders(servletRequest, (HttpRequest)proxyRequest);
        this.setXForwardedForHeader(servletRequest, (HttpRequest)proxyRequest);
        HttpResponse proxyResponse = null;

        try {
            proxyResponse = this.doExecute(servletRequest, servletResponse, (HttpRequest)proxyRequest);
            int statusCode = proxyResponse.getStatusLine().getStatusCode();
            servletResponse.setStatus(statusCode, proxyResponse.getStatusLine().getReasonPhrase());
            this.copyResponseHeaders(proxyResponse, servletRequest, servletResponse);
            if (statusCode == 304) {
                servletResponse.setIntHeader("Content-Length", 0);
            } else {
                this.copyResponseEntity(proxyResponse, servletResponse, (HttpRequest)proxyRequest, servletRequest);
            }
        } catch (Exception var11) {
            this.handleRequestException((HttpRequest)proxyRequest, var11);
        } finally {
            if (proxyResponse != null) {
                EntityUtils.consumeQuietly(proxyResponse.getEntity());
            }
        }
    }

    public void initTarget(String target) throws ServletException{
        this.targetUri = target;
        try {
            this.targetUriObj = new URI(this.targetUri);
        } catch (Exception var2) {
            throw new ServletException("Trying to process targetUri init parameter: " + var2, var2);
        }

        this.targetHost = URIUtils.extractHost(this.targetUriObj);
        this.proxyClient = this.createHttpClient();
    }

    public void setXForwardedForHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest) {

        String forHeaderName = "X-Forwarded-For";
        String forHeader = servletRequest.getRemoteAddr();
        String existingForHeader = servletRequest.getHeader(forHeaderName);
        if (existingForHeader != null) {
            forHeader = existingForHeader + ", " + forHeader;
        }

        proxyRequest.setHeader(forHeaderName, forHeader);
        String protoHeaderName = "X-Forwarded-Proto";
        String protoHeader = servletRequest.getScheme();
        proxyRequest.setHeader(protoHeaderName, protoHeader);

    }

    @Override
    protected HttpClient createHttpClient() {
        HttpClientBuilder clientBuilder = HttpClientBuilder.create().setDefaultRequestConfig(this.buildRequestConfig()).setDefaultSocketConfig(this.buildSocketConfig());
        clientBuilder.setMaxConnTotal(this.maxConnections);
        if (this.useSystemProperties) {
            clientBuilder = clientBuilder.useSystemProperties();
        }

        return clientBuilder.build();
    }

    @Override
    public HttpResponse doExecute(HttpServletRequest servletRequest, HttpServletResponse servletResponse, HttpRequest proxyRequest) throws IOException {
        if (this.doLog) {
            this.log("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
        }

        return this.proxyClient.execute(this.getTargetHost(servletRequest), proxyRequest);
    }

    @Override
    protected String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
        StringBuilder uri = new StringBuilder(500);
        uri.append(this.getTargetUri(servletRequest));
        String pathInfo = this.rewritePathInfoFromRequest(servletRequest);
        if (pathInfo != null) {
            uri.append(encodeUriQuery(pathInfo, true));
        }

        String queryString = servletRequest.getQueryString();
        String fragment = null;
        if (queryString != null) {
            int fragIdx = queryString.indexOf(35);
            if (fragIdx >= 0) {
                fragment = queryString.substring(fragIdx + 1);
                queryString = queryString.substring(0, fragIdx);
            }
        }

        queryString = this.rewriteQueryStringFromRequest(servletRequest, queryString);
        if (queryString != null && queryString.length() > 0) {
            uri.append('?');
            uri.append(encodeUriQuery(queryString, false));
        }

        if (this.doSendUrlFragment && fragment != null) {
            uri.append('#');
            uri.append(encodeUriQuery(fragment, false));
        }

        return uri.toString();
    }

}