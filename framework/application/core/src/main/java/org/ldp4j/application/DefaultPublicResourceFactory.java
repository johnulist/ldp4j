/**
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   This file is part of the LDP4j Project:
 *     http://www.ldp4j.org/
 *
 *   Center for Open Middleware
 *     http://www.centeropenmiddleware.com/
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   Copyright (C) 2014 Center for Open Middleware.
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   Artifact    : org.ldp4j.framework:ldp4j-application-core:1.0.0-SNAPSHOT
 *   Bundle      : ldp4j-application-core-1.0.0-SNAPSHOT.jar
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 */
package org.ldp4j.application;

import static com.google.common.base.Preconditions.checkNotNull;

import org.ldp4j.application.endpoint.Endpoint;
import org.ldp4j.application.resource.Resource;
import org.ldp4j.application.resource.ResourceId;
import org.ldp4j.application.template.ResourceTemplate;
import org.ldp4j.application.template.TemplateIntrospector;

final class DefaultPublicResourceFactory {

	private final DefaultApplicationContext applicationContext;

	private DefaultPublicResourceFactory(DefaultApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	private ResourceTemplate resolveTemplate(Endpoint endpoint) {
		Resource resource = this.applicationContext.resolveResource(endpoint);
		if(resource==null) {
			throw new IllegalStateException("Could not resolve endpoint "+endpoint.path());
		}
		ResourceTemplate template = this.applicationContext.resourceTemplate(resource);
		if(template==null) {
			throw new IllegalStateException("Could not find template for resource "+resource.id());
		}
		return template;
	}

	DefaultPublicResource createResource(ResourceId resourceId) {
		return createResource(this.applicationContext.resolveResource(resourceId));
	}

	DefaultPublicResource createResource(Endpoint endpoint) {
		checkNotNull(endpoint,"Endpoint cannot be null");

		if(endpoint.deleted()!=null) {
			return new DefaultGonePublicResource(this.applicationContext,endpoint);
		}

		ResourceTemplate resourceTemplate = resolveTemplate(endpoint);
		TemplateIntrospector introspector = TemplateIntrospector.newInstance(resourceTemplate);
		DefaultPublicResource resource=null;
		if(introspector.isBasicContainer()) {
			resource=new DefaultPublicBasicContainer(this.applicationContext,endpoint);
		} else if(introspector.isDirectContainer()) {
			resource=new DefaultPublicDirectContainer(this.applicationContext,endpoint);
		} else if(introspector.isIndirectContainer()) {
			resource=new DefaultPublicIndirectContainer(this.applicationContext,endpoint);
		} else { // Assume RDF source
			resource=new DefaultPublicRDFSource(this.applicationContext,endpoint);
		}
		return resource;
	}

	static DefaultPublicResourceFactory newInstance(DefaultApplicationContext applicationContext) {
		return new DefaultPublicResourceFactory(applicationContext);
	}

}
