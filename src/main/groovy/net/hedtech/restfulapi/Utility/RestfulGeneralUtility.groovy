package net.hedtech.restfulapi.Utility

class RestfulGeneralUtility {

    public static boolean isJdk5Enum(Class<?> type) {
        boolean flag = false
        if (RestfulGeneralUtility.getVersion()< 5 && type instanceof Class && ((Class<?>)type).isEnum()){
            flag = true
        }
        return flag
    }

    private static int groovyVersionCheck(){
        String version = System.getProperty("java.runtime.version");
        String[] verArray= version.split(/[.]/)
        String ver = version.startsWith("1.")?verArray[1]: verArray[0]
        return Integer.parseInt(ver)
    }
}
