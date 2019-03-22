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
log4j = {
    warn   'org.mortbay.log'

    error 'org.codehaus.groovy.grails',
            'org.springframework',
            'org.hibernate',
            'net.sf.ehcache.hibernate'
}
restfulApiConfig = {
    // Resources for web_app_extensibility plugin
    resource 'extensions' config {
        //  def a = new BasicDomainClassMarshaller()
        // println a
    }
}
dataSource {
    driverClassName = "oracle.jdbc.OracleDriver"
    url="jdbc:oracle:thin:@localhost:1521:ban83"
    username="ban_ss_user"
    password="u_pick_it"
    dialect = "org.hibernate.dialect.Oracle10gDialect"
    loggingSql = false
}


hibernate {
    cache.use_second_level_cache = true
    cache.use_query_cache = false
    cache.region.factory_class = 'org.hibernate.cache.SingletonEhCacheRegionFactory' // Hibernate 3
//  cache.region.factory_class = 'org.hibernate.cache.ehcache.SingletonEhCacheRegionFactory' // Hibernate 4
    singleSession = true // configure OSIV singleSession mode
    flush.mode = 'manual' // OSIV session flush mode outside of transactional context
    flush.mode = 'manual' // OSIV session flush mode outside of transactional context
//  cache.provider_class = 'net.sf.ehcache.hibernate.EhCacheProvider'
    show_sql = false
    // naming_strategy = "org.hibernate.cfg.ImprovedNamingStrategy"
    dialect = "org.hibernate.dialect.Oracle10gDialect"
    packagesToScan="net.hedtech.**.*, com.sungardhe.*"
    /*config.location = [
            "classpath:events_hibernate.cfg.xml",
            "classpath:cdcadmin_hibernate.cfg.xml",
            "classpath:hibernate-banner-general-utility.cfg.xml"
    ]*/

}

/*
grails.config.locations = [
        BANNER_APP_CONFIG: "banner_configuration.groovy"
]*/
