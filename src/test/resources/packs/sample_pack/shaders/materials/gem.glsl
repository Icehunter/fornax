        // Cut gems: push smoothness toward mirror and lift F0 so facets catch sharp reflections.
        smoothness = max(smoothness, 0.85);
        f0 = max(f0, 0.17);
