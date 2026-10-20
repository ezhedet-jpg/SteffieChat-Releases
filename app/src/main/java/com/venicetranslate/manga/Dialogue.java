package com.venicetranslate.manga;
import android.graphics.Rect;
final class Dialogue {
    final int id;
    final Rect bounds;
    final String english;
    Dialogue(int id, Rect bounds, String english) {
        this.id = id;
        this.bounds = new Rect(bounds);
        this.english = english;
    }
}
