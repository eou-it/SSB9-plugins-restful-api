/* ****************************************************************************
 * Copyright 2013 Ellucian Company L.P. and its affiliates.
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
package net.hedtech.restfulapi.marshallers.json

import grails.converters.JSON
import grails.util.GrailsNameUtils
import grails.util.Holders
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import grails.core.GrailsDomainClass
import grails.core.GrailsDomainClassProperty
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
import org.grails.web.converters.exceptions.ConverterException
import org.springframework.beans.BeanWrapper

class DeclarativeDomainClassMarshaller extends BasicDomainClassMarshaller {

    protected static final Log log =
            LogFactory.getLog(DeclarativeDomainClassMarshaller.class)

    Class supportClass
    def fieldNames = [:]
    def includedFields = null
    boolean requireIncludedFields = false
    def excludedFields = []
    def includeId = true
    def includeVersion = true
    //If a field is a key in the map, then represents
    //a field-level override for whether to marshall that
    //field if null.  Otherise, marshallNullFields is used
    //to determine whether to marshall a field if null
    def marshalledNullFields = [:]
    //default behavior on whether to marshall null fields.
    def marshallNullFields = true
    def marshallEmptyCollections = true

    def additionalFieldClosures = []
    def additionalFieldsMap = [:]
    def fieldResourceNames = [:]
    //if a field is a key in the map, then represents
    //a field-level override
    def deepMarshalledFields = [:]
    //default behavior on whether to shallow or deep
    //marshall association fields
    def deepMarshallAssociations = false
    def shortObjectClosure = DEFAULT_SHORT_OBJECT

    private static DEFAULT_SHORT_OBJECT = { Map map ->
        def resource = map['resourceName']
        def id = map['resourceId']
        def json = map['json']
        def writer = json.getWriter()
        writer.object()
        writer.key("_link").value("/$resource/$id")
        writer.endObject()
    }

// ------------------- Setters ---------------------


// ------------------- End Setters ---------------------

//

    @Override
    public boolean supports(Object object) {
        if (supportClass) {
            supportClass.isInstance(object)
        } else {
            super.supports(object)
        }
    }

    /**
     * Return the name to use when marshalling the field, or
     * null if the field name should be used as-is.
     * @return the name to use when marshalling the field,
     *         or null if the domain field name should be used
     */
    @Override
    protected String getSubstitutionName(BeanWrapper beanWrapper, PersistentProperty property) {
        return fieldNames.get( property.getName() )
    }

    /**
     * Returns the list of fields that should be marshalled
     * for the specified object.
     *<p>
     * If a null or zero-size list is returned, then
     * all fields except those specified by
     * {@link #getExcludedFields(Object) getExcludedFields} and
     * {@link #getCommonExcludedFields} will be marshalled.
     * If a non-zero sized list is returned, then only
     * the fields listed in it are marshalled.  Included fields
     * overrides any skipped fields.  That is, if a field is returned
     * by {@link getIncludedFields(Object) #getIncludedFields} then it
     * will be marshalled even if it is also returned by
     * {@link #getExcludedFields(Object) getExcludedFields} and
     * {@link #getCommonExcludedFields}
     *
     * @return list of field names to marshall
     */
    @Override
    protected List getIncludedFields(Object value) {
        return includedFields
    }

    /**
     * Override whether or not to treat an includes
     * list in a strict fashion or not.  If true then
     * an included field that is not present
     * results in a ConverterException.
     **/
    @Override
    protected boolean requireIncludedFields(Object o) {
        return this.requireIncludedFields
    }


    /**
     * Returns a list of additional fields in the
     * object that should not be marshalled.
     * The complete list of skipped fields is the
     * union of getCommonSkippedFields() and
     * the list returned by this method.
     * Does not apply if {@link #getIncludedFields(Object) getIncludedFields} returns
     * a list containing one or more field names.
     *
     * @param value the object being marshalled
     * @return list of fields that should be skipped
     */
    @Override
    protected List getExcludedFields(Object value) {
        return excludedFields
    }


    /**
     * Override processing of fields.
     * @return true if the marshaller should handle the field in
     *         the default way, false if no further action should
     *         be taken for the field.
     *
     **/
    @Override
    protected boolean processField(BeanWrapper beanWrapper,
                                   PersistentProperty property,
                                   JSON json) {
        boolean ignoreNull = false
        if (marshalledNullFields.containsKey(property.getName())) {
            ignoreNull = !marshalledNullFields[property.getName()]
        } else {
            ignoreNull = !marshallNullFields
        }

        if (ignoreNull) {
            Object val = beanWrapper.getPropertyValue(property.getName())
            if (!marshallEmptyCollections && val instanceof Collection && val.size() == 0) {
                return false
            }
            return val != null
        } else {
            return true
        }
    }

    @Override
    protected void processAdditionalFields(BeanWrapper beanWrapper, JSON json) {
        Map map = [:]
        map.putAll( additionalFieldsMap )
        map.putAll(
                [
                        'grailsApplication':app,
                        'beanWrapper':beanWrapper,
                        'json':json
                ]
        )
        if (!map['resourceName']) {
            map['resourceName'] = getDerivedResourceName(beanWrapper)
        }
        //GrailsDomainClass domainClass = app.getDomainClass(beanWrapper.getWrappedInstance().getClass().getName())
        PersistentEntity domainClass = Holders.getGrailsApplication().getMappingContext().getPersistentEntity(beanWrapper.getWrappedInstance().getClass().getName())
        map['resourceId'] = beanWrapper.getPropertyValue(domainClass.getIdentity().getName())
        additionalFieldClosures.each { c ->
            c.call( map )
        }
    }

    /**
     * Override whether to include an 'id' field
     * for the specified value.
     * @param o the value
     * @return true if an 'id' field should be placed in the
     *         representation
     **/
    @Override
    protected boolean includeIdFor(Object o) {
        return includeId
    }

    /**
     * Override whether to include a 'version' field
     * for the specified value.
     * @param o the value
     * @return true if a 'version' field should be placed in the
     *         representation
     **/
    @Override
    protected boolean includeVersionFor(Object o) {
        return includeVersion
    }

    /**
     * Specify whether to deep marshal a field representing
     * an association, or render it as a short-object.
     * @param beanWrapper the wrapped object containing the field
     * @param property the property to be marshalled
     * @return true if the value of the field should be deep rendered,
     *         false if a short-object representation should be used.
     **/
    @Override
    protected boolean deepMarshallAssociation(BeanWrapper beanWrapper, PersistentProperty property) {
        if (deepMarshalledFields.containsKey(property.getName())) {
            return deepMarshalledFields[property.getName()]
        } else {
            return deepMarshallAssociations
        }
    }

    /**
     * Marshalls an object reference as a json object
     * containing a link to the referenced object as a
     * resource url.
     * @param property the property containing the reference
     * @param refObj the referenced object
     * @param json the JSON converter to marshall to
     */
    @Override
    protected void asShortObject(PersistentProperty property, Object refObj, JSON json) throws ConverterException {
        //GrailsDomainClass refDomainClass = property.getReferencedDomainClass()
        PersistentEntity refDomainClass = ((Association) property).getAssociatedEntity()
        Object id = extractIdForReference( refObj, refDomainClass )
        def resource = fieldResourceNames[property.getName()]
        if (resource == null) {
            def domainName = refDomainClass.getName()
            resource = hyphenate(pluralize(domainName))
        }
        Map map = [
                grailsApplication:app,
                property:property,
                refObject:refObj,
                json:json,
                resourceId:id,
                resourceName:resource
        ]
        this.shortObjectClosure.call(map)
    }
}
