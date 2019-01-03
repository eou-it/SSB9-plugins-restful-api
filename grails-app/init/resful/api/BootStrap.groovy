package resful.api

import net.hedtech.restfulapi.marshallers.xml.BasicDomainClassMarshaller

class BootStrap {

    def init = { servletContext ->
        def a = new BasicDomainClassMarshaller()
        println "from bt " + a
    }
    def destroy = {
    }
}
