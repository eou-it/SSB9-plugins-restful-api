/*******************************************************************************
 Copyright 2020 Ellucian Company L.P. and its affiliates.
 *******************************************************************************/
package net.hedtech.restfulapi

import net.hedtech.restfulapi.config.ResourceConfig


interface RestRuntimeResourceDefinitions {

    //Return a resource config if a runtime resource is defined...otherwise return null
    public ResourceConfig getResourceConfig(String resource);

    public String getGenericConfigName(def acceptHeader);

}