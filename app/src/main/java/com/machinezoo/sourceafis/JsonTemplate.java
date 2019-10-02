// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java.util.*;
import java8.util.Objects;
import java8.util.J8Arrays;
import java8.util.stream.Collectors;
import java8.util.stream.StreamSupport;

class JsonTemplate {
	int width;
	int height;
	List<JsonMinutia> minutiae;
	JsonTemplate(IntPoint size, ImmutableMinutia[] minutiae) {
		width = size.x;
		height = size.y;
		this.minutiae = J8Arrays.stream(minutiae).map(JsonMinutia::new).collect(Collectors.toList());
	}
	IntPoint size() {
		return new IntPoint(width, height);
	}
	ImmutableMinutia[] minutiae() {
		return StreamSupport.stream(minutiae).map(ImmutableMinutia::new).toArray(n -> new ImmutableMinutia[n]);
	}
	void validate() {
		/*
		 * Width and height are informative only. Don't validate them.
		 */
		Objects.requireNonNull(minutiae, "Null minutia array.");
		for (JsonMinutia minutia : minutiae) {
			Objects.requireNonNull(minutia, "Null minutia.");
			minutia.validate();
		}
	}
}
