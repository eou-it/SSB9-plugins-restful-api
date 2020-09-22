/* ******************************************************************************
 Copyright 2020 Ellucian Company L.P. and its affiliates.
 ****************************************************************************** */
package net.hedtech.restfulapi.Utility

import java.util.regex.Pattern

class RestfulGeneralUtility {

    private static Pattern XSS_PATTERN = Pattern.compile("((\\%3C)|<)[^\\n]+((\\%3E)|>)", Pattern.CASE_INSENSITIVE)

    public static boolean isJdk5Enum(Class<?> type) {
        if (RestfulGeneralUtility.checkJdkVersion()< 5 ){
            return false
        }

        if(type instanceof Class && ((Class<?>)type).isEnum()) {
            return true
        }
        else{
            return false
        }
    }

    private static int checkJdkVersion(){
        String version = System.getProperty("java.runtime.version");
        String[] verArray= version.split(/[.]/)
        String ver = version.startsWith("1.")?verArray[1]: verArray[0]
        return Integer.parseInt(ver)
    }

    public static def xssSanitize(def input) {
        if (input != null && input instanceof String) {
            input = XSS_PATTERN.matcher(input).replaceAll("")
        }

        return input;
    }

}
