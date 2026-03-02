package selenium;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.Select;

public class AppPrueba {

    public static void main(String[] args) throws Exception {

        // 1) Conectar al Chromium ya abierto con:
        // chromium-browser --remote-debugging-port=9222 --user-data-dir=...
       /* 
        ChromeOptions options = new ChromeOptions();
        options.setExperimentalOption("debuggerAddress", "localhost:9222");
        WebDriver driver = new ChromeDriver(options);
*/

 String chromeExe = "D:\\Portables\\chrome-win64\\chrome.exe";
    String driverExe = "D:\\Portables\\chromedriver-win64\\chromedriver.exe";

    ChromeOptions options = new ChromeOptions();
    options.setBinary(chromeExe);

    // Recomendado: perfil aislado para Selenium (evita conflictos con Chrome normal)
        options.setExperimentalOption("debuggerAddress", "127.0.0.1:9222");

    options.addArguments("--user-data-dir=D:\\Portables\\chrome-profile-selenium");

    ChromeDriverService service = new ChromeDriverService.Builder()
        .usingDriverExecutable(new File(driverExe))
        .build();

    WebDriver driver = new ChromeDriver(service, options);





        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(30));

        // URL del formulario
        String urlForm = "https://portalempleopublico.juntadeandalucia.es/node/add/cap";
        // String urlForm = "https://portalempleopublicoadm.juntadeandalucia.es/node/add/cap";

        // Ruta del PDF (en WSL)
        String rutaPdf = "d:\\Descargas\\E2017.pdf";

        if (!Files.exists(Path.of(rutaPdf))) {
            throw new RuntimeException("No existe el archivo en WSL: " + rutaPdf);
        }

        // Ir al formulario
        driver.get(urlForm);
        System.out.println("URL actual: " + driver.getCurrentUrl());
        System.out.println("Título: " + driver.getTitle());

        // --- CKEditor: escribir "campo1" ---
        System.out.println("Paso 1: CKEditor (teclado)");

        By editorBy = By.cssSelector("div.ck-editor__editable[contenteditable='true']");
        WebElement editor = wait.until(ExpectedConditions.visibilityOfElementLocated(editorBy));

        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", editor);
        editor.click();

        // limpiar (Ctrl+A + Delete) y escribir
        new org.openqa.selenium.interactions.Actions(driver)
            .keyDown(Keys.CONTROL).sendKeys("a").keyUp(Keys.CONTROL)
            .sendKeys(Keys.DELETE)
            .sendKeys("valor pruebas")
            .perform();

        // opcional: asegurar que CKEditor “registra” el cambio
        editor.sendKeys(Keys.TAB);

        
        
        
        

        // --- Selects normales ---
        System.out.println("Paso 2: Ámbito");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-ambito"))))
                .selectByVisibleText("Administración General");

        System.out.println("Paso 3: Año");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-cap-year"))))
                .selectByVisibleText("2025");

        System.out.println("Paso 4: Tipo provisión");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-tipo-de-provision"))))
                .selectByVisibleText("Funcionario");

        System.out.println("Paso 5: Estado");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(By.id("edit-field-estado-cap"))))
                .selectByVisibleText("Finalizado");

        // --- Legislatura (puede disparar AJAX) ---
        System.out.println("Paso 6: Legislatura");
        new Select(wait.until(ExpectedConditions.elementToBeClickable(
                By.id("edit-field-organismo-legislatura-0-organigram-legislature")
        ))).selectByVisibleText("Legislatura I");

        // Espera simple para que Drupal/Select2 se actualice (AJAX)
        Thread.sleep(1200);

        // --- Select2 Organismo: "CULTURA Y DEPORTE" ---
        System.out.println("Paso 6b: Organismo (Select2)");

        // Este id tiene sufijo variable, por eso usamos "id^="
        By renderedBy = By.cssSelector(
                "span.select2-selection__rendered[id^='select2-edit-field-organismo-legislatura-0-organigram-dependent-term']"
        );

        // Esperar a que exista
        WebElement rendered = wait.until(ExpectedConditions.presenceOfElementLocated(renderedBy));

        // El elemento clicable real es el ancestor con class select2-selection
        WebElement selection = rendered.findElement(By.xpath("./ancestor::span[contains(@class,'select2-selection')]"));

        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", selection);

        // Si está deshabilitado mientras carga, esperar a que se habilite
        wait.until(d -> {
            String cls = selection.getAttribute("class");
            return cls == null || !cls.contains("select2-selection--disabled");
        });

        selection.click();

        WebElement searchBox = wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector("input.select2-search__field")
        ));
        searchBox.sendKeys("CULTURA Y DEPORTE");
        searchBox.sendKeys(Keys.ENTER);

        System.out.println("Organismo seleccionado: CULTURA Y DEPORTE");

        // --- Subir PDF ---
        System.out.println("Paso 7: Subir PDF");
        By fileBy = By.id("edit-field-ficheros-convocatorias-0-subform-field-fichero-adjunto-0-upload");
        WebElement fileInput = wait.until(ExpectedConditions.presenceOfElementLocated(fileBy));

        try {
            fileInput.sendKeys(rutaPdf);
        } catch (ElementNotInteractableException e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].style.display='block';", fileInput);
            fileInput.sendKeys(rutaPdf);
        }
        System.out.println("PDF enviado al input.");

        // --- Descripción del PDF ---
        System.out.println("Paso 8: Descripción");
        By descBy = By.cssSelector(
                "input[data-drupal-selector='edit-field-ficheros-convocatorias-0-subform-field-fichero-adjunto-0-description']"
        );
        WebElement desc = wait.until(ExpectedConditions.elementToBeClickable(descBy));

        String nombreArchivo = java.nio.file.Paths.get(rutaPdf).getFileName().toString();
        desc.clear();
        desc.sendKeys(nombreArchivo + "nombre");

        // --- Fecha adjunto (type=date espera YYYY-MM-DD) ---
        System.out.println("Paso 9: Fecha");
        WebElement fecha = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.id("edit-field-ficheros-convocatorias-0-subform-field-fecha-adjunto-0-value-date")
        ));
        ((JavascriptExecutor) driver).executeScript("arguments[0].value = arguments[1];", fecha, "2025-01-01");

        
        /*
        
        //subimos el adjunto
        System.out.println("Paso 7b: Click botón Adjuntar");

        WebElement botonAdjuntar = wait.until(
            ExpectedConditions.elementToBeClickable(
                By.xpath("//input[@type='submit' and contains(@id,'upload')] | //button[contains(@class,'button')]")
            )
        );

        botonAdjuntar.click();
        */
        System.out.println("Formulario rellenado (hasta aquí).");

        // NO cierres el navegador mientras pruebas
        // driver.quit();
    }
}
