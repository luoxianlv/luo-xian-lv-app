package app.luoxianlv.buildlogic;

import static org.junit.Assert.*;

import java.util.Set;
import org.junit.Test;

public final class ResourceLinkerTest {
  private static final Set<String> SHARED =
      Set.of(
          "style/Theme_Material", "attr/colorPrimary", "color/shared", "id/shared", "array/shared");

  @Test
  public void themesReferencesAndImplicitParentsLinkWithoutEditingText() throws Exception {
    var linker = new ResourceLinker(SHARED);
    var xml =
        ResourceLinker.parse(
            """
<resources>
  <!-- @color/shared 只是注释 -->
  <style name='Example' parent='Theme.Material'><item name='colorPrimary'>@color/shared</item></style>
  <style name='Theme.Material.Child'><item name='android:colorAccent'>?attr/colorPrimary</item></style>
  <string name='description'>说明 @color/shared 保持原样</string>
  <string name='escaped'>\\@color/shared</string>
</resources>
""");
    linker.definitions(xml);
    String output = linker.link(xml);
    assertTrue(output.contains("parent=\"@*app.luoxianlv.runtime:style/Theme.Material\""));
    assertTrue(output.contains("name=\"*app.luoxianlv.runtime:colorPrimary\""));
    assertTrue(output.contains(">@*app.luoxianlv.runtime:color/shared</item>"));
    assertTrue(output.contains("?*app.luoxianlv.runtime:attr/colorPrimary"));
    assertTrue(output.contains("<!-- @color/shared 只是注释 -->"));
    assertTrue(output.contains("说明 @color/shared 保持原样"));
    assertTrue(output.contains("\\@color/shared"));
  }

  @Test
  public void localDefinitionsWinIncludingAliasesArraysAndLayoutIds() throws Exception {
    var linker = new ResourceLinker(SHARED);
    var values =
        ResourceLinker.parse(
            """
            <resources><item type='color' name='shared'>#000000</item>
              <string-array name='shared'><item>本地</item></string-array>
              <style name='Example'><item name='colorPrimary'>@color/shared</item></style>
            </resources>
            """);
    var layout =
        ResourceLinker.parse(
            """
<View xmlns:android='http://schemas.android.com/apk/res/android'
  android:id='@+id/shared' android:tag='@array/shared' android:background='@id/shared'/>
""");
    linker.definitions(values);
    linker.definitions(layout);
    assertTrue(linker.link(values).contains(">@color/shared</item>"));
    assertFalse(linker.link(layout).contains("app.luoxianlv.runtime"));
  }

  @Test
  public void localAttributeDefinitionsAndExplicitPackagesArePreserved() throws Exception {
    var linker = new ResourceLinker(SHARED);
    var xml =
        ResourceLinker.parse(
            """
<resources><declare-styleable name='Widget'><attr name='colorPrimary' format='color'/></declare-styleable>
  <style name='Example' parent=''><item name='colorPrimary'>?colorPrimary</item>
  <item name='android:colorAccent'>@android:color/black</item></style></resources>
""");
    linker.definitions(xml);
    String output = linker.link(xml);
    assertFalse(output.contains("app.luoxianlv.runtime"));
    assertTrue(output.contains("@android:color/black"));
    assertTrue(output.contains("parent=\"\""));
  }

  @Test
  public void externalEntitiesCannotReadBuildMachineFiles() {
    assertThrows(
        Exception.class,
        () ->
            ResourceLinker.parse(
                "<!DOCTYPE resources [<!ENTITY leak SYSTEM"
                    + " 'file:///secret'>]><resources>&leak;</resources>"));
  }
}
