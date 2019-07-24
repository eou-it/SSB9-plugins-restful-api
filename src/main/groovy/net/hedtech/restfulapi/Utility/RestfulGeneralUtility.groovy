package net.hedtech.restfulapi.Utility

class RestfulGeneralUtility {

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
}
