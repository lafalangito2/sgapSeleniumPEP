package prueba;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

public class SmokeTest {
	public static void main(String[] args) {
	    ChromeOptions options = new ChromeOptions();

	    // ✅ esto es el navegador
	    options.setBinary("/usr/bin/chromium-browser");

	    options.addArguments("--headless=new");
	    options.addArguments("--no-sandbox");
	    options.addArguments("--disable-dev-shm-usage");

	    // (opcional) solo si Selenium no encuentra el driver, pero ya lo tienes en /usr/bin/chromedriver
	    // System.setProperty("webdriver.chrome.driver", "/usr/bin/chromedriver");

	    WebDriver driver = new ChromeDriver(options);

	    driver.get("https://example.com");
	    System.out.println("Título: " + driver.getTitle());
	    driver.quit();
	}

}
