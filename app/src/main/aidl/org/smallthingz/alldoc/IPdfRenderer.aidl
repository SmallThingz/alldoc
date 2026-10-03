package org.smallthingz.alldoc;
import android.os.ParcelFileDescriptor;
interface IPdfRenderer {
    int open(in ParcelFileDescriptor file);
    ParcelFileDescriptor render(int page, int width);
    String formFields(int page);
    void writeFormCopy(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, String edits);
    void close();
}
