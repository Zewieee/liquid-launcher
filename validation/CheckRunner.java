package local.launcherfix;

/** app_process on this TV hides uncaught exceptions behind "Killed"; keep failures in stdout. */
public final class CheckRunner {
    public static void main(String[] args) {
        try {
            Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
            System.exit(0);
        }catch(Throwable error) {
            if(error instanceof java.lang.reflect.InvocationTargetException && error.getCause()!=null)error=error.getCause();
            error.printStackTrace(System.out);System.out.flush();System.exit(1);
        }
    }
}
