/**
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   This file is part of the LDP4j Project:
 *     http://www.ldp4j.org/
 *
 *   Center for Open Middleware
 *     http://www.centeropenmiddleware.com/
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 *   Copyright (C) 2014-2016 Center for Open Middleware.
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
 *   Artifact    : org.ldp4j.framework:ldp4j-server-core:0.3.0-SNAPSHOT
 *   Bundle      : ldp4j-server-core-0.3.0-SNAPSHOT.jar
 * #-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=#
 */
package org.ldp4j.server.controller;

import java.net.URI;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.Variant;

import org.ldp4j.application.data.DataSet;
import org.ldp4j.application.engine.context.ApplicationContextException;
import org.ldp4j.application.engine.context.ApplicationExecutionException;
import org.ldp4j.application.engine.context.ContentPreferences;
import org.ldp4j.application.engine.context.OperationPrecondititionException;
import org.ldp4j.application.engine.context.PublicContainer;
import org.ldp4j.application.engine.context.PublicResource;
import org.ldp4j.application.engine.context.UnsupportedInteractionModelException;
import org.ldp4j.application.ext.ApplicationRuntimeException;
import org.ldp4j.application.ext.InvalidContentException;
import org.ldp4j.application.ext.InvalidQueryException;
import org.ldp4j.application.ext.Parameter;
import org.ldp4j.application.ext.Query;
import org.ldp4j.application.ext.UnknownResourceException;
import org.ldp4j.rdf.Namespaces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ExistingEndpointController implements EndpointController {

	private static final String NO_CONSTRAINT_REPORT_ID_DEFINED_ERROR = "No constraint report identifier defined. Full stacktrace follows";

	private static final Logger LOGGER=LoggerFactory.getLogger(ExistingEndpointController.class);

	ExistingEndpointController() {
	}

	private String serialize(OperationContext context, Variant variant, DataSet entity, Namespaces namespaces) {
		return context.serialize(entity,namespaces,variant.getMediaType());
	}

	private void addRequiredHeaders(OperationContext context, ResponseBuilder builder) {
		PublicResource resource = context.resource();
		EndpointControllerUtils.
			populateProtocolEndorsedHeaders(builder,resource.lastModified(),resource.entityTag());
		EndpointControllerUtils.
			populateProtocolSpecificHeaders(builder,resource.getClass());
	}

	private void addOptionsMandatoryHeaders(OperationContext context, ResponseBuilder builder) {
		addRequiredHeaders(context,builder);
		EndpointControllerUtils.
			populateAllowedHeaders(builder, context.resource().capabilities());
		addAcceptPostHeaders(context, builder);
	}


	private void addAcceptPostHeaders(OperationContext context, ResponseBuilder builder) {
		/**
		 * 5.2.3.14
		 */
		for(Variant variant:EndpointControllerUtils.getAcceptPostVariants(context.resource())) {
			builder.header(MoreHttp.ACCEPT_POST_HEADER,variant.getMediaType());
		}
	}

	private Response prepareRetrievalResponse(
			OperationContext context,
			Variant variant,
			boolean hasPreferences,
			ContentPreferences preferences,
			boolean includeEntity,
			String entity,
			Query query) {
		ResponseBuilder builder=Response.serverError();
		builder.variant(variant);
		if(hasPreferences) {
			builder.header(ContentPreferencesUtils.PREFERENCE_APPLIED_HEADER,ContentPreferencesUtils.asPreferenceAppliedHeader(preferences));
		}
		addOptionsMandatoryHeaders(context, builder);
		builder.status(Status.OK.getStatusCode());
		EndpointControllerUtils.populateResponseBody(builder,entity,variant,includeEntity);
		if(!query.isEmpty()) {
			builder.
				header(
					HttpHeaders.LINK,
					EndpointControllerUtils.
						createQueryOfLink(
							context.base().resolve(context.path()),
							query));
		}
		return builder.build();
	}

	private Response prepareConstraintReportRetrievalResponse(OperationContext context, boolean includeEntity, Variant variant, String constraintReportId) {
		try {
			PublicResource resource=context.resource();
			LOGGER.debug("Retrieving constraint report: {}",constraintReportId);

			DataSet report=resource.getConstraintReport(constraintReportId);
			if(report==null) {
				throw new UnknownConstraintReportException(context,constraintReportId,includeEntity);
			}

			LOGGER.trace("Constraint report to serialize:\n{}",report);
			String body=serialize(context,variant,report,NamespacesHelper.constraintReportNamespaces(context.applicationNamespaces()));
			ResponseBuilder builder=Response.ok();
			EndpointControllerUtils.populateResponseBody(builder,body,variant,includeEntity);
			return builder.build();
		} catch (ApplicationExecutionException e) {
			throw diagnoseApplicationExecutionException(context, e);
		} catch (ApplicationContextException e) {
			throw new InternalServerException(context,e);
		}
	}

	private Response handleRetrieval(OperationContext context, boolean includeEntity) {
		final Variant variant=context.expectedVariant();
		context.
			checkOperationSupport().
			checkPreconditions();

		switch(RetrievalScenario.forContext(context)) {
			case MIXED_QUERY:
				throw new MixedQueryNotAllowedException(context,includeEntity);
			case QUERY_NOT_SUPPORTED:
				throw new QueryingNotSupportedException(context,includeEntity);
			case CONSTRAINT_REPORT_RETRIEVAL:
				return handleConstraintReportRetrieval(context,includeEntity,variant);
			default:
				return handleResourceRetrieval(context,includeEntity,variant);
		}
	}

	private Response handleResourceRetrieval(OperationContext context, boolean includeEntity, Variant variant) {
		try {
			if(LOGGER.isDebugEnabled()) {
				LOGGER.debug(EndpointControllerUtils.retrievalLog(context));
			}
			PublicResource resource=context.resource();
			Query query=context.getQuery();
			ContentPreferences preferences = context.contentPreferences();
			boolean hasPreferences = preferences==null;
			if(hasPreferences) {
				preferences=ContentPreferences.defaultPreferences();
			}
			DataSet entity=
				query.isEmpty()?
					resource.entity(preferences):
					resource.query(query,preferences);

			if(LOGGER.isTraceEnabled()) {
				LOGGER.trace(EndpointControllerUtils.retrievalResultLog(entity));
			}

			String body=
				serialize(
					context,
					variant,
					entity,
					NamespacesHelper.
						resourceNamespaces(context.applicationNamespaces()));

			return prepareRetrievalResponse(context,variant,hasPreferences,preferences,includeEntity,body,query);
		} catch (ApplicationExecutionException e) {
			throw diagnoseApplicationExecutionException(context, e);
		} catch (ApplicationContextException e) {
			throw new InternalServerException(context,e);
		}
	}

	private Response handleConstraintReportRetrieval(OperationContext context, boolean includeEntity, Variant variant) {
		Parameter parameter=RetrievalScenario.constraintReportId(context);
		if(parameter.cardinality()!=1) {
			throw new InvalidConstraintReportRetrievalException(context,parameter.rawValues(),includeEntity);
		}
		return prepareConstraintReportRetrievalResponse(context,includeEntity,variant,parameter.rawValue());
	}

	private OperationContextException diagnoseApplicationExecutionException(OperationContext context, ApplicationExecutionException exception) {
		OperationContextException result = null;
		final Throwable rootCause=exception.getCause();
		if(rootCause instanceof InvalidContentException) {
			final InvalidContentException ice = (InvalidContentException)exception.getCause();
			if(ice.getConstraintsId()==null) {
				result=new InternalServerException(context,NO_CONSTRAINT_REPORT_ID_DEFINED_ERROR,ice);
			} else {
				result=new InvalidContentDiagnosedException(context,ice);
			}
		} else if (rootCause instanceof UnknownResourceException) {
			throw new WebApplicationException(Status.NOT_FOUND);
		} else if (rootCause instanceof InvalidQueryException) {
			result=new InvalidQueryDiagnosedException(context, (InvalidQueryException)rootCause);
		} else if (rootCause instanceof ApplicationRuntimeException) {
			result=new InternalServerException(context,rootCause);
		} else {
			result=new InternalServerException(context,exception);
		}
		return result;
	}

	@Override
	public Response options(OperationContext context) {
		ResponseBuilder builder=Response.ok();
		addOptionsMandatoryHeaders(context, builder);
		return builder.build();
	}

	@Override
	public Response head(OperationContext context) {
		return handleRetrieval(context, false);
	}

	@Override
	public Response getResource(OperationContext context) {
		return handleRetrieval(context, true);
	}

	/**
	 * TODO: This could be improved by returning an OK with an additional
	 * description of all the resources that were deleted as a side effect.
	 */
	@Override
	public Response deleteResource(OperationContext context) {
		context.
			checkOperationSupport().
			checkPreconditions();
		try {
			context.resource().delete();
			return Response.noContent().build();
		} catch (ApplicationExecutionException e) {
			throw diagnoseApplicationExecutionException(context, e);
		} catch (ApplicationContextException e) {
			throw new InternalServerException(context,e);
		}
	}

	/**
	 * TODO: This could be improved by returning an OK with an additional
	 * description of all the resources that were modified (updated, created,
	 * deleted) as a side effect.
	 */
	@Override
	public Response modifyResource(OperationContext context) {
		context.
			checkOperationSupport().
			checkContents().
			checkPreconditions();
		try {
			context.resource().modify(context.dataSet());
			ResponseBuilder builder=Response.noContent();
			addRequiredHeaders(context, builder);
			return builder.build();
		} catch (ApplicationExecutionException e) {
			throw diagnoseApplicationExecutionException(context, e);
		} catch (ApplicationContextException e) {
			throw new InternalServerException(context,e);
		}
	}

	@Override
	public Response patchResource(OperationContext context) {
		// Verify that we can carry out the operation
		context.
			checkOperationSupport().
			checkContents().
			checkPreconditions();
		// Fail as we do not support PATCH
		throw new NotImplementedException(context);
	}

	@Override
	public Response createResource(OperationContext context) {
		context.
			checkOperationSupport().
			checkContents().
			checkPreconditions();
		try {
			PublicContainer container=context.container();
			PublicResource newResource =
				container.createResource(context.dataSet(),context.creationPreferences());
			URI location = context.resolve(newResource);
			ResponseBuilder builder=Response.created(location);
			EndpointControllerUtils.populateResponseBody(builder,location.toString(),EndpointControllerUtils.textResponseVariant(),true);
			addRequiredHeaders(context, builder);
			return builder.build();
		} catch (ApplicationExecutionException e) {
			throw diagnoseApplicationExecutionException(context, e);
		} catch (UnsupportedInteractionModelException e) {
			throw new UnsupportedInteractionModelDiagnosedException(context, e);
		} catch (OperationPrecondititionException e) {
			throw new OperationPrecondititionModelDiagnosedException(context, e);
		} catch (ApplicationContextException e) {
			throw new InternalServerException(context,e);
		}
	}
}