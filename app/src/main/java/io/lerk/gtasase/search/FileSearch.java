package io.lerk.gtasase.search;

import android.net.Uri;
import android.util.Pair;

import java.io.File;
import java.util.ArrayList;

/**
 * Utility that does the search for local savegame files.
 *
 * @author Lukas Fülling (lukas@k40s.net)
 */
public interface FileSearch {

    /**
     * Searches for files starting at the given directory.
     *
     * @param searchDirectory the directory to start the search from
     * @return a list of found savegames
     */
    ArrayList<Pair<Uri, File>> search(File searchDirectory);

}