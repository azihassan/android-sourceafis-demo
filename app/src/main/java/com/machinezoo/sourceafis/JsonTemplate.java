// Part of SourceAFIS: https://sourceafis.machinezoo.com
package com.machinezoo.sourceafis;

import java8.util.J8Arrays;
import java8.util.function.Function;

import java.util.*;
import java8.util.function.IntFunction;
import java8.util.stream.Collectors;
import java8.util.stream.StreamSupport;

class JsonTemplate {
	int width;
	int height;
	List<JsonMinutia> minutiae;
	JsonTemplate(Cell size, Minutia[] minutiae) {
		width = size.x;
		height = size.y;
		this.minutiae = J8Arrays.stream(minutiae).map(new Function<Minutia, JsonMinutia>() {
			@Override
			public JsonMinutia apply(Minutia minutia) {
				return new JsonMinutia(minutia);
			}
		}).collect(Collectors.<JsonMinutia>toList());
		//}).collect(toList());
	}
	Cell size() {
		return new Cell(width, height);
	}
	Minutia[] minutiae() {
		return StreamSupport.stream(minutiae).map(new Function<JsonMinutia, Object>() {
			@Override
			public Object apply(JsonMinutia json) {
				return new Minutia(json);
			}
		}).toArray(new IntFunction<Minutia[]>() {
			@Override
			public Minutia[] apply(int n) {
				return new Minutia[n];
			}
		});
	}
}
