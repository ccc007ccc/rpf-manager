package com.gtav.rpfmanager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Test APK owner grants access to its fixtures; it needs no target APK classes. */
public final class GrantTestDocumentsActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String owner = getPackageName();
        Uri tree = DocumentsContract.buildTreeDocumentUri(owner + ".documents", "root");
        grantUriPermission(owner.substring(0, owner.length() - ".test".length()), tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
