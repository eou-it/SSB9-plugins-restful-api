/* ****************************************************************************
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

import grails.plugins.Plugin
import groovy.util.logging.Slf4j
import net.hedtech.restfulapi.RestfulApiController
@Slf4j
class RestfulApiGrailsPlugin extends Plugin{

    def version = "1.7.0"
    def grailsVersion = "3.3.2 > *"
    def pluginExcludes = [
            "grails-app/views/**",
            "web-app/**"
    ]

    def title = "RESTful API Plugin"
    def author = "Charles Hardt, Shane Riddell"
    def authorEmail = "shane_riddell@icloud.com"
    def developers = [
            [name: "Charlie Hardt", email: "chasdev@me.com"]
    ]
    def description = '''\
        |The resful-api plugin facilitates exposing a non-trivial,
        | versioned RESTful API. The plugin provides a DSL that may
        | be used to declaratively specify how resources should be
        | marshalled. Please see the README.md for details.
        |'''.stripMargin()

    def documentation = "https://github.com/restfulapi/restful-api/blob/master/README.md"

    def scm = [url: "https://github.com/restfulapi/restful-api.git"]

    def issueManagement = [ system: "GITHUB", url: "https://github.com/restfulapi/restful-api/issues" ]

    def license = "APACHE"

    void doWithApplicationContext() {
        def artefact = grailsApplication.getArtefactByLogicalPropertyName("Controller", "restfulApi")
        RestfulApiController restfulApiController = applicationContext.getBean(artefact.clazz.name) as RestfulApiController
        restfulApiController.init()
    }
    
}

