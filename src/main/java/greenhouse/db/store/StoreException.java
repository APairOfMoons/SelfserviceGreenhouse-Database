package greenhouse.db.store;

/** 把受检的 {@code SQLException} 包成运行时异常，让上层签名保持干净。 */
public class StoreException extends RuntimeException {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public StoreException(Throwable cause) {
        super(cause);
    }
}
