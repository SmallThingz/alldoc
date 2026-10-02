package org.smallthingz.alldoc;
import android.os.ParcelFileDescriptor;
interface IPdfRenderer {
    int open(in ParcelFileDescriptor file);
    ParcelFileDescriptor render(int page, int width);
    void close();
}
