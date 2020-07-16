/* Copyright 2013-2016 the original author or authors.
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
package net.nosegrind.apiframework

import grails.converters.JSON

import org.springframework.security.authentication.AccountExpiredException
import org.springframework.security.authentication.AuthenticationTrustResolver
import org.springframework.security.authentication.CredentialsExpiredException
import org.springframework.security.authentication.DisabledException
import org.springframework.security.authentication.LockedException
import org.springframework.security.web.WebAttributes

import org.apache.commons.lang3.RandomStringUtils

// google verifier
import com.google.api.client.json.JsonFactory;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.http.HttpTransport;

import grails.plugin.springsecurity.rest.token.AccessToken
import grails.plugin.springsecurity.rest.token.generation.TokenGenerator
import grails.plugin.springsecurity.rest.token.storage.TokenStorageService
import org.springframework.security.core.context.SecurityContextHolder
import grails.plugin.springsecurity.rest.token.rendering.AccessTokenJsonRenderer
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.authority.SimpleGrantedAuthority


class ProviderController{

	/**
	 * Default transportation layer for Google Apis Java client.
	 */
	protected static final HttpTransport TRANSPORT = new NetHttpTransport();

	/**
	 * Default JSON factory for Google Apis Java client.
	 */
	protected static final JsonFactory JSON_FACTORY = new JacksonFactory();

	private String GOOGLE_CLIENT_ID = grailsApplication.config.oauth.providers.google.key as String
	
	/** Dependency injection for the authenticationTrustResolver. */
	AuthenticationTrustResolver authenticationTrustResolver

	/** Dependency injection for the springSecurityService. */
	def springSecurityService

	TokenGenerator tokenGenerator
	TokenStorageService tokenStorageService
	AccessTokenJsonRenderer accessTokenJsonRenderer

	LinkedHashMap auth() {
		println("### provider/auth")
		println(params)


		switch (params.id) {
			case 'google':
				println("### google:"+params)
				def user = checkUser(params.email)
				Set<SimpleGrantedAuthority> auth = new HashSet<>();
				user.authorities.each(){ it ->
						SimpleGrantedAuthority temp= new SimpleGrantedAuthority(it.authority)
						auth.add(temp)
				}

				UserDetails uDeets = new User(user.username, user.password, user.enabled, user.accountExpired, user.passwordExpired, user.accountLocked, auth)

				AccessToken accessToken = tokenGenerator.generateAccessToken(uDeets)
				tokenStorageService.storeToken(accessToken.accessToken, uDeets)
				SecurityContextHolder.context.setAuthentication(accessToken)

				println "Bearer: ${accessToken.accessToken}"

				response.addHeader 'Cache-Control', 'no-store'
				response.addHeader 'Pragma', 'no-cache'
				render contentType: 'application/json', encoding: 'UTF-8',  text:  accessTokenJsonRenderer.generateJson(accessToken)
				break
			case 'ios':
				break
			case 'twitter':
			case 'facebook':
			default:
				break
		}
	}

	private def checkUser(String email){
		println("checkUser called...")

		def Person = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.userLookup.userDomainClassName).newInstance()

		def user = Person.findByEmail(email)
		println(email)
		if(user){
			println("user found")
			// check if if acct is locked or disabled
			if(user.accountLocked || !user.enabled){
				// TODO: send email and alert on site
				Println("User acct locked or disabled. Send email and alert on site")
			}else{
				return user
			}
		}else {
			// else if no user, create user
			user = createUser()
			return user
		}
	}

	private def createUser(){
		println("createUser called...")

		def Person = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.userLookup.userDomainClassName).newInstance()
		def PersonRole = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.userLookup.authorityJoinClassName).newInstance()
		def Role = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.authority.className).newInstance()

		// create username
		Integer inc = 1
		String username = createUsername(inc)
		def user = Person.findByUsername(username)

		while(user){
			inc++
			username = createUsername(inc)
			user = Person.findByUsername(username)
		}

		println("username:"+username)

		PersonRole.withTransaction { status ->
			def userRole = Role.findByAuthority("ROLE_USER")

			if (!user?.id) {
				String password = generatePassword()
				user = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.userLookup.userDomainClassName).newInstance(username: username, password: password, email: params.email)
				user.save(flush: true)
			}

			if (!user?.authorities?.contains(userRole)) {
				def pRole = grailsApplication.getDomainClass(grailsApplication.config.grails.plugin.springsecurity.userLookup.authorityJoinClassName).newInstance(user, userRole)
				pRole.save(flush: true)
			}

			status.isCompleted()
		}

		println(user)

		return user
	}

	private String generatePassword(){
		String charset = (('A'..'Z') + ('0'..'9')).join()
		Integer length = 9
		String password = RandomStringUtils.random(length, charset.toCharArray())
		return password
	}

	String createUsername(Integer inc){
		if(params.fname.size()>inc && params.lname.size()>inc) {
			return (params.fname[0..(inc - 1)]+params.lname[0..-inc]+inc)
		}
	}
}

