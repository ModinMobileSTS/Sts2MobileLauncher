package com.godot.game;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;

public class RepeatSelectionSpinner extends Spinner {
	private AdapterView.OnItemSelectedListener onItemSelectedListener;

	public RepeatSelectionSpinner(Context context) {
		super(context);
	}

	public RepeatSelectionSpinner(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public RepeatSelectionSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	@Override
	public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener listener) {
		super.setOnItemSelectedListener(listener);
		onItemSelectedListener = listener;
	}

	@Override
	public void setSelection(int position) {
		boolean sameSelected = position == getSelectedItemPosition();
		super.setSelection(position);
		notifyIfReselected(position, sameSelected);
	}

	@Override
	public void setSelection(int position, boolean animate) {
		boolean sameSelected = position == getSelectedItemPosition();
		super.setSelection(position, animate);
		notifyIfReselected(position, sameSelected);
	}

	private void notifyIfReselected(int position, boolean sameSelected) {
		if (!sameSelected || onItemSelectedListener == null) {
			return;
		}
		View selectedView = getSelectedView();
		onItemSelectedListener.onItemSelected(this, selectedView, position, getSelectedItemId());
	}
}
