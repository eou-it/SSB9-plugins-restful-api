/* ***************************************************************************
 * Copyright 2013-2018 Ellucian Company L.P. and its affiliates.
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
package net.hedtech.restfulapi.config

import grails.core.GrailsApplication
import net.hedtech.restfulapi.Methods

class ResourceConfig {

    String name
    String serviceName
    String serviceAdapterName
    def methods = [ 'list', 'show', 'create', 'update', 'delete' ]
    def unsupportedMediaTypeMethods = [:]
    def resourceMetadata = [:]
    //use a LinkedHashMap because we want to preserve
    //the order in which representations are added
    def representations = new LinkedHashMap()
    //if set, defines the media type to use for */*
    String anyMediaType
    //if true, verify that an 'id' field in the content matches
    //the id
    boolean idMatchEnforced = true
    //if true, use the Content-Type header to extract body
    //content on DELETE
    boolean bodyExtractedOnDelete = false

    private RestConfig restConfig

    ResourceConfig setServiceName(String name) {
        this.serviceName = name
        return this
    }

    ResourceConfig setServiceAdapterName(String name) {
        this.serviceAdapterName = name
        return this
    }

    ResourceConfig setMethods( def methods ) {
        this.methods = methods
        return this
    }

    ResourceConfig setUnsupportedMediaTypeMethods( def unsupportedMediaTypeMethods ) {
        this.unsupportedMediaTypeMethods = unsupportedMediaTypeMethods
        return this
    }

    ResourceConfig setResourceMetadata( def resourceMetadata ) {
        this.resourceMetadata = resourceMetadata
        return this
    }

    ResourceConfig setIdMatchEnforced(boolean b) {
        this.idMatchEnforced = b
        return this
    }

    ResourceConfig setBodyExtractedOnDelete(boolean b) {
        this.bodyExtractedOnDelete = b
        return this
    }

    boolean allowsMethod( String method ) {
        this.methods.contains( method )
    }

    def getMethods() {
        return this.methods
    }

    boolean allowsMediaTypeMethod( String mediaType, String method ) {
        !this.unsupportedMediaTypeMethods.get(mediaType)?.contains( method )
    }

    def getUnsupportedMediaTypeMethods() {
        return this.unsupportedMediaTypeMethods
    }

    def getResourceMetadata() {
        return this.resourceMetadata
    }

    ResourceConfig representation(Closure c) {
        RepresentationDelegate delegate = new RepresentationDelegate(restConfig)
        c.delegate = delegate
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c.call()
        delegate.mediaTypes.each { mediaType ->
            if (representations.get(mediaType) != null) {
               throw new AmbiguousRepresentationException( resourceName:name, mediaType:mediaType )
            }
            if (!(delegate.representationMetadata instanceof Map)) {
                throw new RepresentationMetadataNotMapException( resourceName: name, mediaType: mediaType )
            }
            RepresentationConfig config = new RepresentationConfig(
                mediaType:mediaType, marshallerFramework:delegate.marshallerFramework,
                contentType:delegate.contentType,
                jsonArrayPrefix:delegate.jsonArrayPrefix,
                marshallers:delegate.marshallers, extractor:delegate.extractor,
                allMediaTypes: delegate.mediaTypes,
                representationMetadata: delegate.representationMetadata,
                representationServiceName: delegate.representationServiceName)

            //if we are using the json or xml marshalling framework, check
            //if we have default marshallers that should be automatically used
            //for all representations, and add then to the configuration
            def framework = config.resolveMarshallerFramework()
            switch(framework) {
                case ~/json/:
                    if (restConfig.hasMarshallerGroup('json')) {
                        def group = restConfig.getMarshallerGroup('json')
                        config.marshallers.addAll(0,group.marshallers)
                    }
                break
                case ~/xml/:
                    if (restConfig.hasMarshallerGroup('xml')) {
                        def group = restConfig.getMarshallerGroup('xml')
                        config.marshallers.addAll(0,group.marshallers)
                    }
                break
                default:
                    break
            }



            representations.put(mediaType, config)
        }
        return this
    }

    RepresentationConfig getRepresentation( String mediaType ) {
        if ('*/*' == mediaType) {
            mediaType = null //clear type, we need to choose one
            if (null != anyMediaType) {
                mediaType = anyMediaType
            } else {
                //pick the first type
                if (representations.size() > 0) {
                    mediaType = representations.entrySet().iterator().next().key
                }
            }
        }
        if (mediaType != null) {
            return representations.get(mediaType)
        }
        return null
    }

    String getResourceName() {
        return name
    }

    void validate() {
        if (!(methods instanceof Collection)) {
            throw new MethodsNotCollectionException( resourceName: name )
        }
        if (methods != null) {
            methods.each {
                if (!(Methods.getAllMethods().contains( it ))) {
                    throw new UnknownMethodException( resourceName:name, methodName: it )
                }
            }
        }
        if (!(unsupportedMediaTypeMethods instanceof Map)) {
            throw new UnsupportedMediaTypeMethodsNotMapException( resourceName: name )
        }
        if (!(resourceMetadata instanceof Map)) {
            throw new ResourceMetadataNotMapException( resourceName: name )
        }
        if (unsupportedMediaTypeMethods != null) {
            unsupportedMediaTypeMethods.each { itEntry ->
                if (representations.get(itEntry.key) == null) {
                    throw new UnsupportedMediaTypeMethodsUnknownMediaTypeException( resourceName: name, mediaType: itEntry.key )
                }
                if (!(itEntry.value instanceof Collection)) {
                    throw new UnsupportedMediaTypeMethodsMapValueNotCollectionException( resourceName: name, mediaType: itEntry.key )
                }
                itEntry.value.each {
                    if (!(Methods.getAllMethods().contains( it ))) {
                        throw new UnknownMediaTypeMethodException( resourceName:name, mediaType:itEntry.key, methodName: it )
                    }
                }
            }
        }
        if (anyMediaType != null && representations.get(anyMediaType) == null) {
            throw new MissingAnyMediaType(resourceName: name, mediaType:anyMediaType)
        }
        representations.entrySet.each {
            it.value.validate()
        }
    }
}
