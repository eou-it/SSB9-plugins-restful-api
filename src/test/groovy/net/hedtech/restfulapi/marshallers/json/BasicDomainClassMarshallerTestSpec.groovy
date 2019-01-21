package net.hedtech.restfulapi.marshallers.json

import org.junit.Rule
import org.junit.rules.TestName
import spock.lang.Specification
import grails.testing.web.controllers.ControllerUnitTest

class BasicDomainClassMarshallerTestSpec extends Specification
        implements ControllerUnitTest<BasicDomainClassMarshaller> {
    @Rule TestName testName = new TestName()

    void setup() {
    }
}
