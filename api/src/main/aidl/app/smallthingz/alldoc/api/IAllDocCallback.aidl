package app.smallthingz.alldoc.api;
import android.os.Bundle;

oneway interface IAllDocCallback {
    void onState(in Bundle status);
}
