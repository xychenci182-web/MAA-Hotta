package com.aliothmoon.maahotta.runtime;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
    byte[] screenshotJpeg(int maxWidth, int quality) = 2;
}
