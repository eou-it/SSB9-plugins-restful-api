

import grails.testing.mixin.integration.Integration
import grails.transaction.*



/**
 * See http://www.gebish.org/manual/current/ for more instructions
 */
@Integration
@Rollback
class RestfulApiControllerSpec{

    def setup() {
    }

    def cleanup() {
    }

    void "test something"() {
        when:"The home page is visited"
            go '/'

        then:"The title is correct"
        	title == "Welcome to Grails"
    }
}
