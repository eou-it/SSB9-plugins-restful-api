/* ***************************************************************************
 * Copyright 2013-2020 Ellucian Company L.P. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *****************************************************************************/

package net.hedtech.restfulapi

import grails.converters.JSON
import grails.converters.XML
import grails.core.GrailsApplication
import grails.util.Holders
import grails.web.http.HttpHeaders
import groovy.util.logging.Slf4j
import net.hedtech.restfulapi.config.RepresentationConfig
import net.hedtech.restfulapi.config.ResourceConfig
import net.hedtech.restfulapi.config.RestConfig
import net.hedtech.restfulapi.exceptionhandlers.*
import net.hedtech.restfulapi.extractors.Extractor
import net.hedtech.restfulapi.extractors.configuration.ExtractorConfigurationHolder
import net.hedtech.restfulapi.marshallers.StreamWrapper
import org.apache.commons.logging.LogFactory
import org.grails.web.converters.exceptions.ConverterException
import org.springframework.context.ApplicationContext


import static java.util.UUID.randomUUID

/**
 * A Restful API controller.
 * This controller delegates to a transactional service
 * corresponding to the resource (via naming convention or
 * configuration) based on the pluralized resource name
 * identified on the URL. This controller may be subclassed
 * to create stateless resource-specific controllers when
 * necessary.  (If a stateful controller is needed, this
 * should not be used as a base class.)
 **/
@Slf4j
class RestfulApiController {

    // Because this controller is stateless, a single instance
    // may be used to handle all requests.
    //
    static scope = "singleton"

    private mediaTypeParser = new MediaTypeParser()

    private RestConfig restConfig

    GrailsApplication grailsApplication

    private messageLog = LogFactory.getLog( 'RestfulApiController_messageLog' )

    private etagGenerator = new EtagGenerator()

    // The default adapter simply passes through the method invocations to the service.
    //
    private RestfulServiceAdapter defaultServiceAdapter =
        [ list:   { def service, Map params                      -> service.list(params) },
          count:  { def service, Map params                      -> service.count(params) },
          show:   { def service, Map params                      -> service.show(params) },
          create: { def service, Map content, Map params         -> service.create(content, params) },
          update: { def service, Map content, Map params -> service.update(content, params) },
          delete: { def service, Map content, Map params -> service.delete(content, params) }
        ] as RestfulServiceAdapter

    private ExtractorAdapter extractorAdapter = new DefaultExtractorAdapter()

    private HandlerRegistry<Throwable,ExceptionHandler> handlerConfig = new DefaultHandlerRegistry<Throwable,ExceptionHandler>()
    def localizingClosure = { mapToLocalize -> this.message( mapToLocalize ) }
    private Localizer localizer = new Localizer(localizingClosure)

    // Custom headers (may be configured within Config.groovy)
    String totalCountHeader
    String pageMaxHeader
    String pageOffsetHeader
    String messageHeader
    String mediaTypeHeader
    String contentRestrictedHeader
    String requestIdHeader

    // Paging query parameter names (configured within Config.groovy)
    String pageMax
    String pageOffset

    // Map of deprecated response headers (optionally configured within Config.groovy)
    Map deprecatedHeaderMap

    // Content extensions (optionally configured within resources.groovy)
    //  - restContentExtensions is configured as a spring bean resource in resources.groovy
    ContentExtensions restContentExtensions

    // Content filter configuration (optionally configured within resources.groovy)
    //  - restContentFilter is configured as a spring bean resource in resources.groovy
    ContentFilter restContentFilter

    RestRuntimeResourceDefinitions restRuntimeResourceDefinitions

    // Force all marshallers to remove null fields and empty collections (optionally configured within Config.groovy)
    boolean marshallersRemoveNullFields
    boolean marshallersRemoveEmptyCollections

    // API Version Parser (optionally configured within resources.groovy)
    //  - apiVersionParser is configured as a spring bean resource in resources.groovy
    // Setting overrideGenericMediaType=true in Config.groovy will replace generic media
    // types with latest actual versioned media types for a representation (where available).
    // The generic media types also need to be configured in genericMediaTypeList property.
    // Setting useHighestSemanticVersion=true in Config.groovy will dynamically replace all
    // versioned media types with the highest semantic version where the major version matches.
    // Setting useAcceptHeaderAsMediaTypeHeader=true in Config.groovy will return
    // the Accept request header as the X-Media-Type response header for some clients to
    // delay transitioning to full semantic versioning of the X-Media-Type response header.
    ApiVersionParser apiVersionParser
    boolean overrideGenericMediaType
    List genericMediaTypeList
    boolean useHighestSemanticVersion
    boolean useAcceptHeaderAsMediaTypeHeader

    private Class pagedResultListClazz

    // Sets a 'request_id' attribute on the request. If an 'X-Request-ID'
    // Header exists (or other configured header serving this purpose),
    // the attribute will be set to that header's value.
    // Otherwise, a UUID will be generated.
    // Preferrably the value will be set by middleware, such as a router.
    // and provided as a Header. Regardless of who sets the value, it will
    // be included in the response as an 'X-Request-ID' Header.
    // This is intended to facilitate troubleshooting and to be included
    // in logging.
    def beforeInterceptor = [action: this.&setRequestIdAttribute, except: 'init']

    /**
     * Initializes the controller by registering the configured marshallers.
     **/
    // NOTE: The timing of PostConstruct works only when running 'test-app'
    //       -- it does *not* work for 'run-app' or 'test-app functional:'.
    //       'init()' is invoked explicitly from RestfulApiGrailsPlugin.
    // @PostConstruct
    void init() {
        initExceptionHandlers()

        log.trace 'Initializing RestfulApiController...'

        totalCountHeader = getHeaderName('totalCount', 'X-hedtech-totalCount')
        pageMaxHeader    = getHeaderName('pageMaxSize', 'X-hedtech-pageMaxSize')
        pageOffsetHeader = getHeaderName('pageOffset', 'X-hedtech-pageOffset')
        messageHeader    = getHeaderName('message', 'X-hedtech-message')
        mediaTypeHeader  = getHeaderName('mediaType', 'X-hedtech-Media-Type')
        contentRestrictedHeader = getHeaderName('contentRestricted', 'X-hedtech-Content-Restricted')
        requestIdHeader  = getHeaderName('requestId', 'X-Request-ID')

        pageMax    = getPagingConfiguration('max', 'max')
        pageOffset = getPagingConfiguration('offset', 'offset')

        deprecatedHeaderMap = getDeprecatedHeaderMap()

        marshallersRemoveNullFields = getMarshallersConfiguration('removeNullFields', false)
        marshallersRemoveEmptyCollections = getMarshallersConfiguration('removeEmptyCollections', false)

        overrideGenericMediaType = getOverrideGenericMediaType()
        genericMediaTypeList = getGenericMediaTypeList()
        useHighestSemanticVersion = getUseHighestSemanticVersion()
        useAcceptHeaderAsMediaTypeHeader = getUseAcceptHeaderAsMediaTypeHeader()

        JSON.createNamedConfig('restapi-error:json') { }
        XML.createNamedConfig('restapi-error:xml') { }

        if (!(grailsApplication.config.restfulApiConfig instanceof Closure)) {
            log.warn( "No restfulApiConfig defined in configuration.  No resources will be exposed.")
        } else {
            restConfig = RestConfig.parse( grailsApplication, grailsApplication.config.restfulApiConfig )
            restConfig.validate()

            // Resource detail list (for reporting and discovery)
                ResourceDetailList resourceDetailList = getSpringBean('resourceDetailList')

            restConfig.resources.values().each() { resource ->
                ResourceDetail resourceDetail = new ResourceDetail()
                if (resourceDetailList) {
                    resourceDetailList.resourceDetails.add(resourceDetail)
                }
                resourceDetail.name = resource.name
                resource.methods.each() { method ->
                    resourceDetail.methods.add(method)
                }
                resource.unsupportedMediaTypeMethods.each() { entry ->
                    resourceDetail.unsupportedMediaTypeMethods.put(entry.key, entry.value)
                }
                resource.resourceMetadata.each() { entry ->
                    resourceDetail.resourceMetadata.put(entry.key, entry.value)
                }
                resource.representations.values().each() { representation ->
                    resourceDetail.mediaTypes.add(representation.mediaType)
                    resourceDetail.representationMetadata.put(representation.mediaType, representation.representationMetadata)
                    if (apiVersionParser) {
                        representation.apiVersion = apiVersionParser.parseMediaType(resource.name, representation.mediaType)
                        if (overrideGenericMediaType) {
                            if (representation.allMediaTypes.size() > 1 && genericMediaTypeList.contains(representation.mediaType)) {
                                List apiVersionList = []
                                representation.allMediaTypes.each { mediaType ->
                                    if (!genericMediaTypeList.contains(mediaType)) {
                                        apiVersionList.add(apiVersionParser.parseMediaType(resource.name, mediaType))
                                    }
                                }
                                if (apiVersionList.size() > 0) {
                                    representation.apiVersion = apiVersionList.sort().get(apiVersionList.size() - 1)
                                }
                            }
                        }
                        if (useHighestSemanticVersion && representation.apiVersion.version) {
                            if (representation.allMediaTypes.size() > 1 && !genericMediaTypeList.contains(representation.mediaType)) {
                                List apiVersionList = []
                                representation.allMediaTypes.each { mediaType ->
                                    if (!genericMediaTypeList.contains(mediaType)) {
                                        def testApiVersion = apiVersionParser.parseMediaType(resource.name, mediaType)
                                        if (testApiVersion.majorVersion == representation.apiVersion.majorVersion) {
                                            apiVersionList.add(testApiVersion)
                                        }
                                    }
                                }
                                if (apiVersionList.size() > 0) {
                                    representation.apiVersion = apiVersionList.sort().get(apiVersionList.size() - 1)
                                }
                            }
                        }
                    }
                    def framework = representation.resolveMarshallerFramework()
                    switch(framework) {
                        case ~/json/:
                            JSON.createNamedConfig("restfulapi:" + resource.name + ":" + representation.mediaType) { json ->
                                log.info "Creating named config: 'restfulapi:${resource.name}:${representation.mediaType}'"
                                representation.marshallers.each() {
                                    log.info "    ...registering json marshaller ${it.instance}"
                                    json.registerObjectMarshaller(it.instance,it.priority)
                                    if (marshallersRemoveNullFields && it.instance.hasProperty("marshallNullFields")) {
                                        it.instance.marshallNullFields = false
                                    }
                                    if (marshallersRemoveEmptyCollections && it.instance.hasProperty("marshallEmptyCollections")) {
                                        it.instance.marshallEmptyCollections = false
                                    }
                                }
                            }
                        break
                        case ~/xml/:
                            XML.createNamedConfig("restfulapi:" + resource.name + ":" + representation.mediaType) { xml ->
                                log.info "Creating named config: 'restfulapi:${resource.name}:${representation.mediaType}'"
                                representation.marshallers.each() {
                                    log.info "    ...registering xml marshaller ${it.instance}"
                                    xml.registerObjectMarshaller(it.instance,it.priority)
                                    if (marshallersRemoveNullFields && it.instance.hasProperty("marshallNullFields")) {
                                        it.instance.marshallNullFields = false
                                    }
                                    if (marshallersRemoveEmptyCollections && it.instance.hasProperty("marshallEmptyCollections")) {
                                        it.instance.marshallEmptyCollections = false
                                    }
                                }
                            }
                        break
                        default:
                            break
                    }
                    //register the extractor (if any)
                    if (null != representation.extractor) {
                        log.info "registering extractor ${representation.extractor} for resource '${resource.name}'' and media type '${representation.mediaType}'"
                        ExtractorConfigurationHolder.registerExtractor(resource.name, representation.mediaType, representation.extractor )
                    }
                }
            }

            // sort the resource detail list by resource name
            if (resourceDetailList) {
                resourceDetailList.resourceDetails.sort { a, b -> a.name <=> b.name }
            }

            restConfig.exceptionHandlers.each { config ->
                log.info "registering exception handler class " + config.instance.getClass().getName() + " at priority " + config.priority
                handlerConfig.add(config.instance, config.priority)
            }
            if (log.isInfoEnabled()) {
                def sb = new StringBuffer()
                sb.append "Registered exception handler order is:\n"
                handlerConfig.getOrderedHandlers().each { handler->
                    sb.append(handler.getClass().getName() + "\n")
                }
                log.info sb.toString()
            }
        }

        //see if we are running with hibernate and need to support PagedList
        //as of grails 2.3, this class is in the hibernate plugin, not
        //core, and we don't want direct hibernate dependencies
        try {
            pagedResultListClazz = Class.forName('grails.orm.PagedResultList')
        } catch (ClassNotFoundException) {
            //not using hibernate support
        }

        // register content filter
        restContentFilter = getSpringBean('restContentFilter')
        if (restContentFilter) {
            log.trace "Registered restContentFilter spring bean"
        }

        // register api version parser
        apiVersionParser = getSpringBean('apiVersionParser')
        if (apiVersionParser) {
            log.trace "Registered apiVersionParser spring bean"
        }

        log.trace 'Done initializing RestfulApiController...'
    }


// ---------------------------------- ACTIONS ---------------------------------


    // GET /api/pluralizedResourceName
    //
    public def list() {
        log.trace "list invoked for ${params.pluralizedResourceName} - request_id=${request.request_id}"

        try {
            ResourceConfig resourceConfig = getResourceConfig()
            checkMethod(Methods.LIST, resourceConfig)
            def responseRepresentation = getResponseRepresentation(resourceConfig) // adds representation attribute to request
            checkMediaTypeMethod( responseRepresentation.mediaType, Methods.LIST, resourceConfig )

            def requestParams = params  // accessible from within withCacheHeaders
            def logger = log            // ditto

            if (request.method == "POST") {
                def queryCriteria = parseRequestContent( request, 'query-filters', Methods.LIST, params.pluralizedResourceName, resourceConfig )
                updatePagingQueryParams( queryCriteria ) // We'll ensure params uses expected Grails naming
                requestParams << queryCriteria
            }
            else {
                updatePagingQueryParams( requestParams ) // We'll ensure params uses expected Grails naming
            }

            def service = getRepresentationService(responseRepresentation, resourceConfig)
            if(!service) {
                service = getService(resourceConfig)
            }
            def delegateToService = getServiceAdapter(resourceConfig)
            logger.trace "... will delegate list() to service $service using adapter $delegateToService"

            def result = delegateToService.list(service, requestParams)
            logger.trace "... service returned $result"

            def count
            if ((null != pagedResultListClazz) && (pagedResultListClazz.isInstance(result))) {
                count = result.totalCount
            } else if (result instanceof PagedResultList) {
                count = result.getTotalCount()
            } else {
                count = delegateToService.count(service, requestParams)
            }

            def hasTotalCount = true
            if (result instanceof PagedResultArrayList) {
                hasTotalCount = result.hasTotalCount
            }

            // Need to create etagValue outside of 'etag' block:
            // http://jira.grails.org/browse/GPCACHEHEADERS-14
            String etagValue = etagGenerator.shaFor( result, count, responseRepresentation.mediaType )

            withCacheHeaders {
                etag {
                    etagValue
                }
                delegate.lastModified {
                    lastModifiedFor( result )
                }
                    ResponseHolder holder = new ResponseHolder()
                    holder.data = result
                    if (hasTotalCount) {
                        holder.addHeader(totalCountHeader, count)
                    }
                    holder.addHeader(pageOffsetHeader, requestParams.offset ? requestParams?.offset : 0)
                    holder.addHeader(pageMaxHeader, requestParams.max ? requestParams?.max : result.size())
                    if (request.method == "POST") {
                        holder.isQapi = true
                    }
                generate {
                    renderSuccessResponse(holder, 'default.rest.list.message', resourceConfig)
                }
            }
        }
        catch (e) {
            log.trace "Error in list method:"+e
            logMessageError(e)
            renderErrorResponse(e, resourceConfig)
            return
        }
        finally{
            log.trace "Invalidating the session in list method"
            try {session.invalidate()} catch(sessionInvalidateError) {}
        }
    }


    // GET /api/pluralizedResourceName/id
    //
    public def show() {
        log.trace "show() invoked for ${params.pluralizedResourceName}/${params.id} - request_id=${request.request_id}"

        try {
            ResourceConfig resourceConfig = getResourceConfig()
            checkMethod(Methods.SHOW, resourceConfig)
            def responseRepresentation = getResponseRepresentation(resourceConfig)
            checkMediaTypeMethod( responseRepresentation.mediaType, Methods.SHOW, resourceConfig )

            def requestParams = params  // accessible from within withCacheHeaders
            def logger = log            // ditto

            //check to veify if its a custom-resource
            if(resourceConfig?.serviceName == "specDrivenAPIDataModelFacadeService"){
                params?.serviceName = "specDrivenAPIDataModelFacadeService"
            }

            def result = getServiceAdapter(resourceConfig).show( getService(resourceConfig), requestParams )
            // Need to create etagValue outside of 'etag' block:
            // http://jira.grails.org/browse/GPCACHEHEADERS-14
            String etagValue = etagGenerator.shaFor( result, responseRepresentation.mediaType )

            withCacheHeaders {

                etag {
                    etagValue
                }
                delegate.lastModified {
                    if (hasProperty( result, "lastUpdated" ))       result.lastUpdated
                    else if (hasProperty( result, "lastModified" )) result.lastModified
                    else                                            new Date()
                }
                generate {
                    renderSuccessResponse( new ResponseHolder( data: result ),
                            'default.rest.shown.message', resourceConfig )
                }
            }
        }
        catch (e) {
            log.trace "Error in show method:"+e
            logMessageError(e)
            renderErrorResponse(e, resourceConfig)
        }
        finally{
            log.trace "Invalidating the session in show method"
            try {session.invalidate()} catch(sessionInvalidateError) {}
        }
    }


    // POST /api/pluralizedResourceName
    //
    public def create() {
        log.trace "create() invoked for ${params.pluralizedResourceName} - request_id=${request.request_id}"
        def result

        try {
            ResourceConfig resourceConfig = getResourceConfig()
            checkMethod(Methods.CREATE, resourceConfig)
            def content = parseRequestContent( request, params.pluralizedResourceName, Methods.CREATE, resourceConfig )
            log.trace "Extracted content $content"
            getResponseRepresentation(resourceConfig)
            result = getServiceAdapter(resourceConfig).create( getService(resourceConfig), content, params )
            response.setStatus( 201 )
            renderSuccessResponse( new ResponseHolder( data: result ),
                    'default.rest.created.message', resourceConfig )
        }
        catch (e) {
            log.trace "Error in create method:"+e
            logMessageError(e)
            renderErrorResponse(e, resourceConfig)
        }
        finally{
            log.trace "Invalidating the session in create method"
            try {session.invalidate()} catch(sessionInvalidateError) {}
        }
    }


    // PUT/PATCH /api/pluralizedResourceName/id
    //
    public def update() {
        log.trace "update() invoked for ${params.pluralizedResourceName}/${params.id} - request_id=${request.request_id}"
        def result

        try {
            ResourceConfig resourceConfig = getResourceConfig()
            checkMethod(Methods.UPDATE, resourceConfig)
            def content = parseRequestContent( request, params.pluralizedResourceName, Methods.UPDATE, resourceConfig )
            checkId(content, resourceConfig)
            getResponseRepresentation(resourceConfig)
            result = getServiceAdapter(resourceConfig).update( getService(resourceConfig), content, params )
            response.setStatus( 200 )
            renderSuccessResponse( new ResponseHolder( data: result ),
                    'default.rest.updated.message', resourceConfig )
        }
        catch (e) {
            log.trace "Error in update method:"+e
            logMessageError(e)
            renderErrorResponse(e, resourceConfig)
        }
        finally{
            log.debug "Invalidating session in update method"
            try {session.invalidate()} catch(sessionInvalidateError) {}
        }
    }


    // DELETE /api/pluralizedResourceName/id
    //
    public def delete() {
        log.trace "delete() invoked for ${params.pluralizedResourceName}/${params.id} - request_id=${request.request_id}"

        try {
            ResourceConfig resourceConfig = getResourceConfig()
            checkMethod(Methods.DELETE, resourceConfig)
            def content = [:]
            if (resourceConfig.bodyExtractedOnDelete) {
                content = parseRequestContent( request, params.pluralizedResourceName, Methods.DELETE, resourceConfig )
            } else {
                def types = mediaTypeParser.parse( request.getHeader(HttpHeaders.CONTENT_TYPE) )
                types.each { type ->
                    checkMediaTypeMethod( type.name, Methods.DELETE, resourceConfig )
                }
            }
            checkId(content, resourceConfig)
            getServiceAdapter(resourceConfig).delete( getService(resourceConfig), content, params )
            response.setStatus( 200 )
            renderSuccessResponse(new ResponseHolder(), 'default.rest.deleted.message', resourceConfig)
        }
        catch (e) {
            log.debug "Error in delete method:"+e
            logMessageError(e)
            renderErrorResponse(e, resourceConfig)
        }
        finally{
            log.debug "Invalidating session in delete method"
            try {session.invalidate()} catch(sessionInvalidateError) {}
        }
    }


// ---------------------------- Helper Methods -------------------------------


    /**
     * Renders a successful response using the supplied map and msg resource code.
     * A message property with a value translated from the message resource code
     * provided with a localized and singularized resource name will be automatically
     * added to the map.
     * @param responseMap the Map to render
     * @param msgResourceCode the resource code used to create a message entry
     **/
    protected void renderSuccessResponse(ResponseHolder holder, String msgResourceCode, ResourceConfig resourceConfig) {
        String localizedName = localize(Inflector.singularize(params.pluralizedResourceName))
        holder.message = message( code: msgResourceCode, args: [ localizedName ] )
        if (request.request_id) holder.addHeader(requestIdHeader, request.request_id)
        renderResponse(holder, resourceConfig)
    }


    /**
     * Renders an error response appropriate for the exception.
     * @param e the exception to render an error response for
     **/
    protected void renderErrorResponse( Throwable e, ResourceConfig resourceConfig ) {
        ResponseHolder responseHolder = createErrorResponse(e)
        if (request.request_id) responseHolder.addHeader(requestIdHeader, request.request_id)
        //The versioning applies to resource representations, not to
        //errors.  In fact, it can't, as the error may be that an unrecognized format
        //was requested.  So if we are returning an error response, we switch the format
        //to either json or xml.
        //So we'll look at the Accept-Header directly and try to determine if JSON or XML was
        //requested.  If we can't decide, we will return JSON.
        String contentType = null
        def content
        MediaType[] acceptedTypes = mediaTypeParser.parse(request.getHeader(HttpHeaders.ACCEPT))
        def type = acceptedTypes.size() > 0 ? acceptedTypes[0].name : ""
        switch(type) {
            case ~/.*xml.*/:
                contentType = 'application/xml'
                if (responseHolder.data != null) {
                    useXML("restapi-error:xml") {
                        content = responseHolder.data as XML
                    }
                }
                break
            default:
                contentType = 'application/json'
                if (responseHolder.data != null) {
                    useJSON("restapi-error:json") {
                        content = responseHolder.data as JSON
                    }
                }
                break
        }

        if (responseHolder.message) {
            responseHolder.addHeader( messageHeader, responseHolder.message )
        }

        // Add any deprecated response headers to the response holder
        applyDeprecatedHeaderMap(responseHolder.headers)

        responseHolder.headers.each { header ->
            header.value.each() { val ->
                response.addHeader( header.key, val )
            }
        }

        render(text: content ? content : "", contentType: contentType )
    }


    protected ResponseHolder createErrorResponse( Throwable e ) {
        ResponseHolder responseHolder = new ResponseHolder()
        try {
            def handler = handlerConfig.getHandler(e)
            ExceptionHandlerContext context = new ExceptionHandlerContext(
                    pluralizedResourceName:params.pluralizedResourceName,
                    localizer:localizer)

            ErrorResponse result = handler.handle(e, context)
            if (result.headers) {
                result.headers.each() { header ->
                    if (header.value instanceof Collection) {
                        header.value.each { val ->
                            responseHolder.addHeader( header.key, val )
                        }
                    } else {
                        responseHolder.addHeader( header.key, header.value )
                    }
                }
            }
            responseHolder.data = result.content
            responseHolder.message = result.message
            this.response.setStatus(result.httpStatusCode)
        }
        catch (t) {
            //We generated an exception trying to generate an error response.
            //Log the error, and attempt to fall back on a generic fail-whale response
            log.error( "Caught exception attemping to prepare an error response: ${t.message}", t )
            responseHolder.data = null

            responseHolder.message = message( code: 'default.rest.unexpected.exception.messages' )
            this.response.setStatus( 500 )
        }
        return responseHolder
    }


    protected String selectContentTypeForResponse( RepresentationConfig representation ) {
        def result = representation.contentType
        if (null == result) {
            switch(representation.mediaType) {
                case ~/.*json$/:
                    result = 'application/json'
                    break
                case ~/.*xml$/:
                    result = 'application/xml'
                    break
                default:
                    result = representation.mediaType
                    break
            }
        }
        result
    }


    protected def generateResponseContent( RepresentationConfig representation, def data, ResourceConfig resourceConfig ) {
        def result
        def framework = representation.resolveMarshallerFramework()

        if (null == framework) {
            //if we can't determine a framework by this point,
            //we have no idea how to marshall a response.
            //note that this should never happen, as we should
            //have checked for this before ever delegating to a service
            unsupportedResponseRepresentation()
        }

        switch(framework) {
            case ~/json/:
                log.trace "Going to useJSON with representation $representation"
                useJSON(resourceConfig, representation) {
                    result = (data as JSON) as String

                    // add a prefix if configured to protect from a JSON Array
                    // vulnerability to CSRF attack.
                    if (data instanceof Collection) {
                        if (representation.jsonArrayPrefix instanceof String) {
                            result = representation.jsonArrayPrefix + result
                        }
                    }
                }
                break
            case ~/xml/:
                log.trace "Going to useXML with representation $representation"
                useXML(resourceConfig, representation) {
                    result = (data as XML) as String
                }
                break
            default:
                log.trace "Going to use custom marshaller service $framework with representation $representation"
                def service = getMarshallingService(framework)
                result = service.marshalObject(data,representation)
                break
        }
        result
    }


    /**
     * Renders the content of the supplied map using a registered converter.
     * @param responseMap the Map containing the data and headers to render
     * @param format if specified, use the as the response format.  Otherwise
     *        use the format on the response (taken from the Accept-Header)
     * @param mediaType if specified, use as the media type for the response.
     *        Otherwise, use the media-type type specified by the Accept header.
     **/
    protected void renderResponse( ResponseHolder responseHolder, ResourceConfig resourceConfig ) {
        //def acceptedTypes = mediaTypeParser.parse( request.getHeader(HttpHeaders.ACCEPT) )
        def representation
        def content
        def contentType

        if (responseHolder.data != null) {
            representation = getResponseRepresentation(resourceConfig)
            content = generateResponseContent( representation, responseHolder.data, resourceConfig )
            contentType = selectContentTypeForResponse( representation )
        }

        if (content != null) {
            String responseMediaType = representation.mediaType
            String apiVersionMediaType = representation.apiVersion?.mediaType
            if (apiVersionMediaType) {
                if ((useHighestSemanticVersion && !useAcceptHeaderAsMediaTypeHeader) ||
                        (overrideGenericMediaType && genericMediaTypeList.contains(responseMediaType))) {
                    responseMediaType = apiVersionMediaType
                }
            }
            responseHolder.addHeader( mediaTypeHeader, responseMediaType )
        }

        if (responseHolder.message) {
            responseHolder.addHeader( messageHeader, responseHolder.message )
        }

        // Add any deprecated response headers to the response holder
        applyDeprecatedHeaderMap(responseHolder.headers)

        responseHolder.headers.each { header ->
            header.value.each() { val ->
                response.addHeader( header.key, val )
            }
        }

        if (content != null) {
            // optional: perform content extension post representation
            if (restContentExtensions && isExtensibleContent(content, contentType)) {
                log.trace("Extending content for resource=$params.pluralizedResourceName with contentType=$contentType")
                def result = restContentExtensions.applyExtensions(params.pluralizedResourceName, request, params, content, responseHolder.isQapi)
                if (result.extensionsApplied) {
                    content = result.content
                }
            }


            // optional: perform filtering of response content
            if (restContentFilter && isFilterableContent(content, contentType)) {
                log.trace("Filtering content for resource=$params.pluralizedResourceName with contentType=$contentType")
                def result = restContentFilter.applyFilter(params.pluralizedResourceName, content, contentType)
                if (result.isPartial) {
                    content = result.content
                    response.addHeader( contentRestrictedHeader, 'partial' )
                }
            }

            if (content instanceof byte[]) {
                response.setContentType(contentType)
                response.setContentLength(content.length)
                def out = response.getOutputStream()
                out.write(content)
                out.flush()
                out.close()
            } else if (content instanceof InputStream) {
                response.setContentType(contentType)
                def out = response.getOutputStream()
                out << content
                out.flush()
                out.close()
            } else if (content instanceof StreamWrapper) {
                response.setContentType(contentType)
                response.setContentLength(content.totalSize)
                def out = response.getOutputStream()
                out << content.stream
                out.flush()
                out.close()
            } else {
                render(text: content, contentType: contentType )
            }
        } else {
            render(text:"", contentType:'text/plain')
        }
    }


    protected boolean isExtensibleContent( def content, def contentType ) {
        return (content && content instanceof String &&
                (contentType == "application/json"))
    }


    protected boolean isFilterableContent( def content, def contentType ) {
        return (content && content instanceof String &&
                (contentType == "application/json" ||
                        contentType == "application/xml"))
    }


    protected boolean hasProperty( Object obj, String name ) {
        obj.getMetaClass().hasProperty(obj, "$name") && obj."$name"
    }


    protected Date lastModifiedFor( Collection resourceModels ) {

        if (!resourceModels) return new Date()

        Date latestDate
        resourceModels.each {
            if (hasProperty( it, 'lastUpdated' )) {
                if (it.lastUpdated > latestDate) latestDate = it.lastUpdated
            }
            else if (hasProperty( it, 'lastModified' )) {
                if (it.lastModified > latestDate) latestDate = it.lastModified
            }
        }
        latestDate ?: new Date()
        latestDate
    }


    /**
     * Parses the content from the request.
     * Returns a map representing the properties of content.
     * @param request the request containing the content
     **/
    protected Map parseRequestContent( request, String resource, String method, String actualResource = null, ResourceConfig resourceConfig ) {

        def isUsingQueryFilters = (resource == 'query-filters')
        if (isUsingQueryFilters && actualResource && useHighestSemanticVersion) {
            resource = actualResource
        }
        def representation = getRequestRepresentation( resource, resourceConfig )

        checkMediaTypeMethod( representation.mediaType, method, resourceConfig )

        Extractor extractor = ExtractorConfigurationHolder.getExtractor( resourceConfig.name, representation.mediaType )
        if (!extractor) {
            unsupportedRequestRepresentation()
        }

        def extractorAdapter = getExtractorAdapter()

        // optional: perform filtering of request content, except for these cases:
        //  - qapi requests (a form of query using the content body in place of params)
        //  - delete method which only requires the key of a resource
        //  - create requests if configured to bypass
        //  - update requests if configured to bypass
        if (restContentFilter && !(isUsingQueryFilters ||
                method == Methods.DELETE ||
                (method == Methods.CREATE && restContentFilter.bypassCreateRequest) ||
                (method == Methods.UPDATE && restContentFilter.bypassUpdateRequest))) {
            def contentType = selectContentTypeForResponse( representation )
            log.trace("Filtering content for resource=$resource with contentType=$contentType")
            try {
                ContentFilterHolder.set([
                        contentFilter: restContentFilter,
                        resourceName: resource,
                        contentType: contentType
                ])
                return extractorAdapter.extract(extractor, request)
            } finally {
                ContentFilterHolder.clear()
            }
        }

        return extractorAdapter.extract(extractor, request)
    }


    /**
     * Returns the name of the service to which this controller will delegate.
     * This implementation assumes the resource is a Grails 'domain'
     * object, and that the service name can be constructed by using the pluralized
     * 'resource' name found on the URL and appending 'Service'.
     * For example: If a URL of /api/pluralizedResourceName/id was invoked,
     * a service name of 'SingularizedResourceNameService' will be returned.
     **/
    protected String getServiceName(ResourceConfig resourceConfig) {
        def svcName = resourceConfig?.serviceName
        if (svcName == null) {
            svcName = "${domainName()}Service"
        }
        log.trace "getServiceName() will return $svcName"
        svcName
    }


    /**
     * Returns the transactional service corresponding to this resource.
     * The default implementation assumes the resource is a Grails 'domain'
     * object, and that the service can be identified by using the pluralized
     * 'resource' name found on the URL.
     * For example: If a URL of /api/pluralizedResourceName/id was invoked,
     * a service named 'SingularizedResourceNameService' will be retrieved
     * from the IoC container.
     *
     **/
    protected def getService(ResourceConfig resourceConfig) {
        def svc = getSpringBean(getServiceName(resourceConfig))
        log.trace "getService() will return service $svc"
        if (null == svc) {
            if (resourceConfig != null) {
                svc = getSpringBean(resourceConfig.getServiceName());
            }
            if (null == svc) {
                log.warn "No service found for resource ${params.pluralizedResourceName}"
                throw new UnsupportedResourceException(params.pluralizedResourceName)
            }
        }
        log.trace "getService() will return service $svc"
        svc
    }


    protected def getMarshallingService(String name) {
        def svc = getSpringBean( name, true )
        log.trace "getMarshallingService() will return service $svc"
        svc
    }


    /**
     * Returns the name of the optional per-service adapter to use.
     * This implementation assumes the adapter is a spring bean
     * implementing the RestfulServiceAdapter interface.
     **/
    protected String getServiceAdapterName(ResourceConfig resourceConfig) {
        def name = resourceConfig?.serviceAdapterName
        log.trace "getServiceAdapterName() will return $name"
        name
    }


    /**
     * Returns an adapter supporting the service.
     * This will look for a service-specific adapter configured within the
     * 'restfulApiServiceAdapters' map (if registered in the Spring container).
     * Next, the restfulApiServiceAdapters map will be checked to see if an
     * adapter is registered for 'any' service.
     * If a service-specific adapter was not found, this method will look for a
     * 'global' adapter within the Spring container using the name 'restfulServiceAdapter'.
     * If no adapter is found in the Spring container, this
     * implementation will return a built-in pass-through adapter.
     **/
    protected RestfulServiceAdapter getServiceAdapter(ResourceConfig resourceConfig) {
        def adapter
        def adapterName = getServiceAdapterName(resourceConfig)
        if (null != adapterName) {
            adapter = getSpringBean( getServiceAdapterName(resourceConfig) )
            if (null == adapter) {
                //if we can't find the per-resource adapter that was configured,
                //do not continue.  The resource is not configured correctly and
                //cannot be supported.
                log.warn "Could not locate bean for ${adapterName} configured as the service adapter for resource ${params.pluralizedResourceName}; "
                throw new UnsupportedResourceException(params.pluralizedResourceName)
            }
        }

        // We'll see if there is a global adapter defined
        if (null == adapter) {
            adapter = getSpringBean( 'restfulServiceAdapter' )
        }

        //if no adapter, we'll use the default
        adapter = adapter ?: defaultServiceAdapter
        log.trace "getServiceAdapter() will return adapter $adapter"
        adapter
    }


    protected def getRepresentationService(def responseRepresentation, ResourceConfig resourceConfig) {
        //getRepresentation
        def serviceName = responseRepresentation?.representationServiceName
        if(!serviceName) {
            serviceName = resourceConfig?.representations?.get(responseRepresentation?.mediaType)?.representationServiceName
        }
        log.trace "getRepresentationService() will return $serviceName"
        def svc = getSpringBean( serviceName )
        log.trace "getService() will return service $svc"
        if (null == svc) {
            log.warn "No representation service found for resource ${params.pluralizedResourceName}"
        }
        log.trace "representationService() will return service $svc"
        svc
    }


    protected def getSpringBean( String beanName, boolean required = false ) {

        log.trace "Looking for a Spring bean named $beanName"
        def bean
        try {

            ApplicationContext ctx = (ApplicationContext) Holders.grailsApplication.getMainContext()
            bean = ctx.getBean(beanName)
        } catch (e) { // it is not an error if we cannot find an adapter
            if (required) {
                log.error "Did not find a bean named $beanName - ${e.message}", e
                throw e
            } else {
                log.trace "Did not find a bean named $beanName - ${e.message}"
            }
        }
        bean
    }


    protected ExtractorAdapter getExtractorAdapter() {
        extractorAdapter
    }


    /**
     * Returns the best match or null if no supported representation for the resource exists.
     **/
    protected RepresentationConfig getRepresentation(pluralizedResourceName, allowedTypes, ResourceConfig resourceConfig) {
        RepresentationConfig representationConfig = restConfig.getRepresentation(pluralizedResourceName, allowedTypes.name);
        if (representationConfig == null) {
            if (resourceConfig != null) {
                representationConfig = resourceConfig.getRepresentation(allowedTypes.name);
            }
        }
        return representationConfig
    }


    protected void checkMethod(String method, ResourceConfig resourceConfig) {
        if (!resourceConfig) {
            throw new UnsupportedResourceException( params.pluralizedResourceName )
        }
        if (!resourceConfig.allowsMethod( method ) ) {
            def allowed = resourceConfig.getMethods().intersect( Methods.getMethodGroup( method ) )
            throw new UnsupportedMethodException( supportedMethods:allowed )
        }
    }

    protected void checkMediaTypeMethod( String mediaType, String method, ResourceConfig resourceConfig ) {
        if (!resourceConfig) {
            throw new UnsupportedResourceException( params.pluralizedResourceName )
        }
        if (!resourceConfig.allowsMediaTypeMethod( mediaType, method ) ) {
            def allowed = resourceConfig.getMethods().intersect( Methods.getMethodGroup( method ) ) - resourceConfig.getUnsupportedMediaTypeMethods().get(mediaType)
            throw new UnsupportedMethodException( supportedMethods:allowed )
        }
    }

    protected checkId(Map content, ResourceConfig resourceConfig) {
        if (content && content.containsKey('id') && resourceConfig.idMatchEnforced) {
            String contentId = content.id == null ? null : content.id.toString()
            if (contentId != params.id) {
                throw new IdMismatchException( params.pluralizedResourceName )
            }
        }
    }

    protected logMessageError(Throwable e) {
        messageLog.error "Caught exception: ${e.message != null ? e.message : ''}", e
    }


    private String getHeaderName(name, defaultString) {
        def value = grailsApplication.config.restfulApi.header."${name}"
        (value instanceof String) ? value : defaultString
    }


    private String getPagingConfiguration(name, defaultString) {
        def value = grailsApplication.config.restfulApi.page."${name}"
        (value instanceof String) ? value : defaultString
    }


    private Map getDeprecatedHeaderMap() {
        def value = grailsApplication.config.restfulApi.deprecatedHeaderMap
        (value instanceof Map) ? value : [:]
    }


    private void applyDeprecatedHeaderMap(headers) {
        deprecatedHeaderMap?.each { entry ->
            def value = headers[entry.key]
            if (value) {
                if (entry.value instanceof List) {
                    entry.value.each { item ->
                        headers[item] = value
                    }
                } else {
                    headers[entry.value] = value
                }
            }
        }
    }


    private boolean getOverrideGenericMediaType() {
        def value = grailsApplication.config.restfulApi.overrideGenericMediaType
        (value instanceof Boolean) ? value : false
    }


    private List getGenericMediaTypeList() {
        def value = grailsApplication.config.restfulApi.genericMediaTypeList
        (value instanceof List) ? value : []
    }


    private boolean getUseHighestSemanticVersion() {
        def value = grailsApplication.config.restfulApi.useHighestSemanticVersion
        (value instanceof Boolean) ? value : false
    }


    private boolean getUseAcceptHeaderAsMediaTypeHeader() {
        def value = grailsApplication.config.restfulApi.useAcceptHeaderAsMediaTypeHeader
        (value instanceof Boolean) ? value : false
    }


    private boolean getMarshallersConfiguration(name, defaultBoolean) {
        def value = grailsApplication.config.restfulApi.marshallers."${name}"
        (value instanceof Boolean) ? value : defaultBoolean
    }


    private setRequestIdAttribute() {
        // we'll set an attribute that may be used for logging
        if (request.getHeader(requestIdHeader)) {
            request.request_id = request.getHeader(requestIdHeader)
        }
        else {
            request.request_id = randomUUID()
        }
    }


    private void updatePagingQueryParams(requestParams) {
        if (pageMax != 'max' || pageOffset != 'offset') {
            if (requestParams."$pageMax")    requestParams.max = requestParams."$pageMax"
            if (requestParams."$pageOffset") requestParams.offset = requestParams."$pageOffset"
        }
    }


    private String localize(String name) {
        message( code: "${name}.label", default: "$name" )
    }


    private String domainName() {
        Inflector.asPropertyName(params.pluralizedResourceName)
    }


    /**
     * If we try to use an unknown configuration for a grails converter, a ConverterException
     * is thrown, which can't be programmatically distinguished from other marshalling errors.
     * So we'll test for the existence of the named configuration upfront, so if we don't
     * support it, we can return an appropriate error response.
     **/
    private Object useJSON( String config, Closure closure ) {
        boolean notFound =false;
        try {
            JSON.getNamedConfig( config )
        } catch (ConverterException e) {
            //Create one a converter on the fly....
            notFound = true;
        }
        if (notFound){
            if (restRuntimeResourceDefinitions != null){
                boolean customNotFound=false;
                String customConfig = restRuntimeResourceDefinitions.getGenericConfigName( request.getHeader(HttpHeaders.ACCEPT))
                try {
                    JSON.getNamedConfig( customConfig )
                } catch (ConverterException e) {
                    //Create one a converter on the fly....
                    customNotFound = true;
                }
                if (customNotFound) {
                    //Get the custom name and try to use it.
                    throw new UnsupportedResponseRepresentationException(params.pluralizedResourceName, request.getHeader(HttpHeaders.ACCEPT))
                }else{
                    config = customConfig
                }
            }else{
                //failure to retrieve the named config.  Treat as an unknown format.
                throw new UnsupportedResponseRepresentationException( params.pluralizedResourceName, request.getHeader(HttpHeaders.ACCEPT) )
            }
        }
        JSON.use(config,closure)
    }


    private Object useJSON(ResourceConfig resourceConfig, RepresentationConfig config, Closure closure) {
        useJSON( "restfulapi:" + resourceConfig.name + ":" + config.mediaType, closure )
    }


    /**
     * If we try to use an unknown configuration for a grails converter, a ConverterException
     * is thrown, which can't be programmatically distinguished from other marshalling errors.
     * So we'll test for the existence of the named configuration upfront, so if we don't
     * support it, we can return an appropriate error response.
     **/
    private Object useXML( String config, Closure closure ) {
        try {
            XML.getNamedConfig( config )
        } catch (ConverterException e) {
            //failure to retrieve the named config.  Treat as an unknown format.
            throw new UnsupportedResponseRepresentationException( params.pluralizedResourceName, request.getHeader(HttpHeaders.ACCEPT) )
        }
        XML.use(config,closure)
    }


    private Object useXML(ResourceConfig resourceConfig,  RepresentationConfig config, Closure closure) {
        useXML( "restfulapi:" + resourceConfig.name + ":" + config.mediaType, closure )
    }


    private RepresentationConfig getResponseRepresentation(ResourceConfig resourceConfig) {
        def representation = request.getAttribute( RepresentationRequestAttributes.RESPONSE_REPRESENTATION )
        if (representation == null) {
            def acceptedTypes = mediaTypeParser.parse( request.getHeader(HttpHeaders.ACCEPT) )
            representation = getRepresentation(params.pluralizedResourceName, acceptedTypes, resourceConfig)
            if (representation == null || representation.resolveMarshallerFramework() == null) {
                //if no representation, or the representation does not have a marshaller framework,
                //then this is a representation that cannot be marshalled to.
                unsupportedResponseRepresentation()
            }
            request.setAttribute( RepresentationRequestAttributes.RESPONSE_REPRESENTATION, representation )
        }
        return representation
    }


    private RepresentationConfig getRequestRepresentation( String resource = params.pluralizedResourceName, ResourceConfig resourceConfig ) {
        def representation = request.getAttribute( RepresentationRequestAttributes.REQUEST_REPRESENTATION )
        if (representation == null) {
            def types = mediaTypeParser.parse(request.getHeader(HttpHeaders.CONTENT_TYPE))
            def type = types.size() > 0 ? [types[0]] : []
            representation = getRepresentation(resource, type, resourceConfig)
            if (representation == null) {
                unsupportedRequestRepresentation()
            }
            request.setAttribute( RepresentationRequestAttributes.REQUEST_REPRESENTATION, representation )
        }
        return representation
    }


    private unsupportedResponseRepresentation() {
        throw new UnsupportedResponseRepresentationException( params.pluralizedResourceName, request.getHeader(HttpHeaders.ACCEPT) )
    }


    private unsupportedRequestRepresentation() {
        throw new UnsupportedRequestRepresentationException( params.pluralizedResourceName, request.getHeader(HttpHeaders.CONTENT_TYPE ) )
    }


    private ResourceConfig getResourceConfig(String resource = params.pluralizedResourceName) {
        ResourceConfig resourceConfig = restConfig.getResource( resource ) ;
        if (resourceConfig == null){
            if (restRuntimeResourceDefinitions) {
                resourceConfig = restRuntimeResourceDefinitions.getResourceConfig(resource);
            }
        }
        return resourceConfig
    }

    private initExceptionHandlers() {
        handlerConfig.add(new UnsupportedMethodExceptionHandler(), -8)
        handlerConfig.add(new IdMismatchExceptionHandler(), -7)
        handlerConfig.add(new UnsupportedResponseRepresentationExceptionHandler(), -6)
        handlerConfig.add(new UnsupportedRequestRepresentationExceptionHandler(), -5)
        handlerConfig.add(new UnsupportedResourceExceptionHandler(), -4 )
        handlerConfig.add(new ValidationExceptionHandler(), -3)
        handlerConfig.add(new OptimisticLockExceptionHandler(), -2)
        handlerConfig.add(new ApplicationExceptionHandler(), -1)

        handlerConfig.add(new DefaultExceptionHandler(), Integer.MIN_VALUE)
    }
}