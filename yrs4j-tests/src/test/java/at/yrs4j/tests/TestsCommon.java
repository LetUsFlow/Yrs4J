package at.yrs4j.tests;

import at.yrs4j.api.Yrs4J;
import at.yrs4j.libnative.linux.LinuxLibLoader;
import at.yrs4j.libnative.windows.WindowsLibLoader;
import at.yrs4j.wrapper.interfaces.YDoc;
import at.yrs4j.wrapper.interfaces.YOptions;
import org.junit.jupiter.api.BeforeAll;

abstract class TestsCommon {
    @BeforeAll
    static void initYrs() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("windows")) {
            Yrs4J.init(WindowsLibLoader.create());
        } else if (osName.contains("linux")) {
            Yrs4J.init(LinuxLibLoader.create());
        } else {
            throw new IllegalStateException("Unsupported operating system: " + osName);
        }
    }

    protected YDoc createYDocWithId(long id) {
        YOptions options = YOptions.create();
        options.setId(id);
        return YDoc.createWithId(options);
    }
}
