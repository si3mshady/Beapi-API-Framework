/*
 * Academic Free License ("AFL") v. 3.0
 * Copyright 2014-2017 Owen Rubel
 *
 * IO State (tm) Owen Rubel 2014
 * API Chaining (tm) Owen Rubel 2013
 *
 *   https://opensource.org/licenses/AFL-3.0
 */

package grails.api.framework;

import grails.plugin.springsecurity.rest.RestAuthenticationProvider
import grails.plugin.springsecurity.rest.authentication.RestAuthenticationEventPublisher
import grails.plugin.springsecurity.rest.token.AccessToken
import grails.plugin.springsecurity.rest.token.reader.TokenReader
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.web.filter.GenericFilterBean

import org.springframework.web.context.request.RequestContextHolder as RCH

import javax.annotation.Resource
import javax.servlet.FilterChain
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.xml.ws.Service

import grails.util.Metadata

import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.context.ApplicationContext

//import grails.plugin.cache.GrailsCacheManager
import org.grails.plugin.cache.GrailsCacheManager
import grails.util.Holders

import javax.servlet.http.HttpSession
import org.springframework.web.context.request.RequestContextHolder as RCH

@Slf4j
//@CompileStatic
class TokenCacheValidationFilter extends GenericFilterBean {

    String headerName

    RestAuthenticationProvider restAuthenticationProvider

    AuthenticationSuccessHandler authenticationSuccessHandler
    AuthenticationFailureHandler authenticationFailureHandler
    RestAuthenticationEventPublisher authenticationEventPublisher

    TokenReader tokenReader
    String validationEndpointUrl
    Boolean active

    Boolean enableAnonymousAccess
    GrailsCacheManager grailsCacheManager

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = request as HttpServletRequest
        HttpServletResponse httpResponse = response as HttpServletResponse
        AccessToken accessToken

        try {
            accessToken = tokenReader.findToken(httpRequest)
            if (accessToken) {
                //log.debug "Token found: ${accessToken.accessToken}"

                accessToken = restAuthenticationProvider.authenticate(accessToken) as AccessToken

                if (accessToken.authenticated) {
                    //log.debug "Token authenticated. Storing the authentication result in the security context"
                    //log.debug "Authentication result: ${accessToken}"
                    SecurityContextHolder.context.setAuthentication(accessToken)

                    //authenticationEventPublisher.publishAuthenticationSuccess(accessToken)

                    processFilterChain(request, response, chain, accessToken)
                }else{
                    httpResponse.status = 401
                    httpResponse.setHeader('ERROR', 'Unauthorized Access attempted')
                    httpResponse.writer.flush()
                    //return
                }
            } else {
                //log.debug "Token not found"
                return
            }
        } catch (AuthenticationException ae) {
            httpResponse.status = 401
            httpResponse.setHeader('ERROR', 'Authorization Attempt Failed')
            httpResponse.writer.flush()
            //authenticationEventPublisher.publishAuthenticationFailure(ae, accessToken)
            //authenticationFailureHandler.onAuthenticationFailure(httpRequest, httpResponse, ae)
        }

    }

    @CompileDynamic
    private void processFilterChain(ServletRequest request, ServletResponse response, FilterChain chain, AccessToken authenticationResult) {
        HttpServletRequest httpRequest = request as HttpServletRequest
        HttpServletResponse httpResponse = response as HttpServletResponse

        String actualUri = httpRequest.requestURI - httpRequest.contextPath

        if (!active) {
            println("Token validation is disabled. Continuing the filter chain")
            return
        }

        if (authenticationResult?.accessToken) {
            if (actualUri == validationEndpointUrl) {
                //log.debug "Validation endpoint called. Generating response."

                authenticationSuccessHandler.onAuthenticationSuccess(httpRequest, httpResponse, authenticationResult)
            } else {
                String entryPoint = Metadata.current.getProperty(Metadata.APPLICATION_VERSION, String.class)
                String controller
                String action

                if(actualUri ==~ /\/.{0}[a-z]${entryPoint}\/(.*)/){
                    List params = actualUri.split('/')
                    controller = params[2]
                    action = params[3]
                }else{
                    httpResponse.status = 401
                    httpResponse.setHeader('ERROR', 'BAD Access attempted')
                    //httpResponse.writer.flush()
                    return
                }

                ApplicationContext ctx = Holders.grailsApplication.mainContext
                GrailsCacheManager grailsCacheManager = ctx.getBean("grailsCacheManager");

                LinkedHashMap cache = [:]
                def temp = grailsCacheManager?.getCache('ApiCache')
                def tempCache = temp?.get(controller.toString())

                if(tempCache?.get()){
                    cache = tempCache.get() as LinkedHashMap
                    HttpSession session = httpRequest.getSession()
                    session['cache'] = cache as LinkedHashMap
                }

                String version = cache['cacheversion']

                if(!cache?."${version}"?."${action}"){
                    httpResponse.status = 401
                    httpResponse.setHeader('ERROR', 'IO State Not properly Formatted for this URI. Please contact the Administrator.')
                    //httpResponse.writer.flush()
                    return
                }else{
                    def session = RCH.currentRequestAttributes().getSession()
                    session['cache'] = cache
                }

                HashSet roles = cache?."${version}"?."${action}"?.roles as HashSet

                if(!checkAuth(roles,authenticationResult)) {
                    httpResponse.status = 401
                    httpResponse.setHeader('ERROR', 'Unauthorized Access attempted')
                    //httpResponse.writer.flush()
                    return
                }else {
                    //log.debug "Continuing the filter chain"
                }
            }
        } else {
            //println("Request does not contain any token. Letting it continue through the filter chain")
        }

        chain.doFilter(request, response)
    }


    boolean checkAuth(HashSet roles, AccessToken accessToken){
        HashSet tokenRoles = []
        accessToken.getAuthorities()*.authority.each() { tokenRoles.add(it) }
        try {
            if (roles.size()==1 && roles[0] == 'permitAll') {
                return true
            } else if(roles.intersect(tokenRoles).size()>0) {
                return true
            }
            return false
        }catch(Exception e) {
            //println("[TokenCacheValidationFilter :: checkAuth] : Exception - full stack trace follows:"+e)
            throw new Exception("[TokenCacheValidationFilter :: checkAuth] : Exception - full stack trace follows:",e)
        }
    }
}
