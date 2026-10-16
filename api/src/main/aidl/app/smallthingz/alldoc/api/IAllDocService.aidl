package app.smallthingz.alldoc.api;
import android.os.ParcelFileDescriptor;
import app.smallthingz.alldoc.api.IAllDocSession;
import app.smallthingz.alldoc.api.IAllDocCallback;

interface IAllDocService {
    int version();
    IAllDocSession open(in ParcelFileDescriptor input, String name, String mime, String password,
                        IAllDocCallback callback);
}
