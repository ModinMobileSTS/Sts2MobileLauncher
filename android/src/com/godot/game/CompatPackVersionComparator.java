package com.godot.game;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompatPackVersionComparator {
	private static final Pattern VERSION = Pattern.compile(
		"v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
			+ "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
			+ "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

	private CompatPackVersionComparator() {}

	/** A null result means one of the legacy versions does not have a defined order. */
	static Integer compare(int leftRevision, String leftVersion, int rightRevision, String rightVersion) {
		if (leftRevision > 0 && rightRevision > 0) {
			return Integer.compare(leftRevision, rightRevision);
		}
		if (leftVersion == null || rightVersion == null) {
			return null;
		}
		Matcher left = VERSION.matcher(leftVersion);
		Matcher right = VERSION.matcher(rightVersion);
		if (!left.matches() || !right.matches()) {
			return null;
		}
		String[] leftPre = identifiers(left.group(4));
		String[] rightPre = identifiers(right.group(4));
		if (!validPrerelease(leftPre) || !validPrerelease(rightPre)) {
			return null;
		}
		for (int i = 1; i <= 3; i++) {
			int order = compareNumber(left.group(i), right.group(i));
			if (order != 0) {
				return order;
			}
		}
		if (leftPre == null || rightPre == null) {
			return Boolean.compare(leftPre == null, rightPre == null);
		}
		for (int i = 0; i < Math.min(leftPre.length, rightPre.length); i++) {
			boolean leftNumeric = isNumeric(leftPre[i]);
			boolean rightNumeric = isNumeric(rightPre[i]);
			int order = leftNumeric && rightNumeric ? compareNumber(leftPre[i], rightPre[i])
				: leftNumeric != rightNumeric ? Boolean.compare(rightNumeric, leftNumeric)
				: leftPre[i].compareTo(rightPre[i]);
			if (order != 0) {
				return order;
			}
		}
		return Integer.compare(leftPre.length, rightPre.length);
	}

	private static String[] identifiers(String prerelease) {
		return prerelease == null ? null : prerelease.split("\\.");
	}

	private static boolean validPrerelease(String[] segments) {
		if (segments != null) {
			for (String segment : segments) {
				if (segment.length() > 1 && segment.charAt(0) == '0' && isNumeric(segment)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isNumeric(String segment) {
		for (int i = 0; i < segment.length(); i++) {
			char ch = segment.charAt(i);
			if (ch < '0' || ch > '9') {
				return false;
			}
		}
		return true;
	}

	private static int compareNumber(String left, String right) {
		int lengthOrder = Integer.compare(left.length(), right.length());
		return lengthOrder == 0 ? left.compareTo(right) : lengthOrder;
	}
}
