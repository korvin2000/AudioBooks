package com.fanok.audiobooks.sync;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.fanok.audiobooks.R;

/**
 * Asks for the code a cloud's sign-in page shows in the browser, which cannot hand it back to the app. A dialog
 * fragment, so it comes back with the sign-in's {@link #STATE} when the screen is recreated while the user is in the
 * browser. The answer is the host's fragment result {@code key}: {@link #CODE} and {@link #STATE}.
 */
public final class CodeDialog extends DialogFragment {

    static final String CODE = "code", STATE = "state";
    private static final String KEY = "key", TITLE = "title", MESSAGE = "message";

    static void ask(@NonNull Fragment host, @NonNull String key, @StringRes int title, @StringRes int message,
            @NonNull String state) {
        Bundle args = new Bundle();
        args.putString(KEY, key);
        args.putInt(TITLE, title);
        args.putInt(MESSAGE, message);
        args.putString(STATE, state);
        CodeDialog dialog = new CodeDialog();
        dialog.setArguments(args);
        dialog.show(host.getChildFragmentManager(), key);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        EditText code = new EditText(requireContext());
        code.setId(android.R.id.edit); // keeps what was typed across a recreation
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        FrameLayout box = new FrameLayout(requireContext());
        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(margin, 0, margin, 0);
        box.addView(code);
        return new AlertDialog.Builder(requireContext())
                .setTitle(args.getInt(TITLE))
                .setMessage(args.getInt(MESSAGE))
                .setView(box)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Bundle result = new Bundle();
                    result.putString(CODE, code.getText().toString().trim());
                    result.putString(STATE, args.getString(STATE));
                    getParentFragmentManager().setFragmentResult(args.getString(KEY, ""), result);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
    }
}
