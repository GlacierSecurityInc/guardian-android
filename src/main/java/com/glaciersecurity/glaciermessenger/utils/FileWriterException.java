package com.glaciersecurity.glaciermessenger.utils;

import java.io.File;

public class FileWriterException extends Exception {

    public FileWriterException(File file) {
        super(String.format("Could not write to %s", file.getAbsolutePath()));
    }

    FileWriterException() {

    }
}
