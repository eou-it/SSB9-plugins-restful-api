/* ****************************************************************************
 * Copyright 2013-2016 Ellucian Company L.P. and its affiliates.
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
package net.hedtech.restfulapi.extractors.json

import net.hedtech.restfulapi.ContentFilterHolder
import net.hedtech.restfulapi.extractors.JSONExtractor
import net.hedtech.restfulapi.extractors.ProtectedFieldException

import org.grails.web.json.JSONObject

/**
 * Default extractor for JSON content.
 **/
class DefaultJSONExtractor implements JSONExtractor {

    Map extract( JSONObject content ) {
        if (content) {
            def contentFilterHolder = ContentFilterHolder.get()
            if (contentFilterHolder) {
                def contentFilter = contentFilterHolder.contentFilter
                def result = contentFilter.applyFilter(
                        contentFilterHolder.resourceName,
                        content,
                        contentFilterHolder.contentType)
                if (result.isPartial) {
                    if (contentFilter.allowPartialRequest) {
                        content = result.content
                    } else {
                        throw new ProtectedFieldException(contentFilterHolder.resourceName)
                    }
                }
            }
        }
        return content
    }

}
