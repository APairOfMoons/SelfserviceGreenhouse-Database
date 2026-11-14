package greenhouse.db.util;

/** 静默关闭，停机流程里一个组件失败不该影响下一个。 */
public final class Closeables {

    private Closeables() {
    }

    /** 关闭并吞掉异常；需要记录时由调用方在调用前后自己记。 */
    public static void quietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 停机路径上不再抛
        }
    }
}
