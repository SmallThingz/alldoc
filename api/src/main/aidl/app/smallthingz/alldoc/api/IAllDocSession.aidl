package app.smallthingz.alldoc.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IAllDocSession {
    Bundle status();
    ParcelFileDescriptor read(long requestId, String format, int page, int width,
                              String theme, boolean preserveImages);
    void cancel(long requestId);
    void close();
    ParcelFileDescriptor readSheet(long requestId, int sheet, double x, double y, double scale,
                                   int width, int height, String theme, String imageTreatment);
}
