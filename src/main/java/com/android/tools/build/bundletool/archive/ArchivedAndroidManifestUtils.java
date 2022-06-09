/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.archive;

import static com.android.tools.build.bundletool.model.AndroidManifest.ANDROID_NAMESPACE_URI;
import static com.android.tools.build.bundletool.model.AndroidManifest.LAUNCHER_CATEGORY_NAME;
import static com.android.tools.build.bundletool.model.AndroidManifest.LEANBACK_FEATURE_NAME;
import static com.android.tools.build.bundletool.model.AndroidManifest.LEANBACK_LAUNCHER_CATEGORY_NAME;
import static com.android.tools.build.bundletool.model.AndroidManifest.MAIN_ACTION_NAME;
import static com.android.tools.build.bundletool.model.AndroidManifest.META_DATA_GMS_VERSION;
import static com.android.tools.build.bundletool.model.AndroidManifest.TOUCHSCREEN_FEATURE_NAME;
import static com.google.common.base.Preconditions.checkNotNull;

import com.android.tools.build.bundletool.model.AndroidManifest;
import com.android.tools.build.bundletool.model.ManifestEditor;
import com.android.tools.build.bundletool.model.manifestelements.Activity;
import com.android.tools.build.bundletool.model.manifestelements.IntentFilter;
import com.android.tools.build.bundletool.model.manifestelements.Receiver;
import com.android.tools.build.bundletool.model.utils.xmlproto.XmlProtoElementBuilder;
import com.android.tools.build.bundletool.model.utils.xmlproto.XmlProtoNode;
import com.android.tools.build.bundletool.model.version.BundleToolVersion;
import com.google.common.collect.ImmutableList;
import java.util.Optional;

/** Utility methods for creation of archived manifest. */
public final class ArchivedAndroidManifestUtils {
  public static final String META_DATA_KEY_ARCHIVED = "com.android.vending.archive";

  public static final String REACTIVATE_ACTIVITY_NAME =
      "com.google.android.archive.ReactivateActivity";
  public static final String HOLO_LIGHT_NO_ACTION_BAR_THEME =
      "@android:style/Theme.Holo.Light.NoActionBar";

  public static final String UPDATE_BROADCAST_RECEIVER_NAME =
      "com.google.android.archive.UpdateBroadcastReceiver";
  public static final String MY_PACKAGE_REPLACED_ACTION_NAME =
      "android.intent.action.MY_PACKAGE_REPLACED";

  // Resource IDs
  public static final ImmutableList<Integer> MANIFEST_ATTRIBUTES_TO_KEEP =
      ImmutableList.of(
          AndroidManifest.VERSION_CODE_RESOURCE_ID,
          AndroidManifest.VERSION_NAME_RESOURCE_ID,
          AndroidManifest.SHARED_USER_ID_RESOURCE_ID,
          AndroidManifest.SHARED_USER_LABEL_RESOURCE_ID,
          AndroidManifest.TARGET_SANDBOX_VERSION_RESOURCE_ID);

  // Resource IDs
  public static final ImmutableList<Integer> APPLICATION_ATTRIBUTES_TO_KEEP =
      ImmutableList.of(
          AndroidManifest.DESCRIPTION_RESOURCE_ID,
          AndroidManifest.HAS_FRAGILE_USER_DATA_RESOURCE_ID,
          AndroidManifest.IS_GAME_RESOURCE_ID,
          AndroidManifest.ICON_RESOURCE_ID,
          AndroidManifest.BANNER_RESOURCE_ID,
          AndroidManifest.LABEL_RESOURCE_ID,
          AndroidManifest.FULL_BACKUP_ONLY_RESOURCE_ID,
          AndroidManifest.FULL_BACKUP_CONTENT_RESOURCE_ID,
          AndroidManifest.DATA_EXTRACTION_RULES_RESOURCE_ID,
          AndroidManifest.RESTRICTED_ACCOUNT_TYPE_RESOURCE_ID,
          AndroidManifest.REQUIRED_ACCOUNT_TYPE_RESOURCE_ID,
          AndroidManifest.LARGE_HEAP_RESOURCE_ID);

  // Names
  public static final ImmutableList<String> CHILDREN_ELEMENTS_TO_KEEP =
      ImmutableList.of(
          AndroidManifest.USES_SDK_ELEMENT_NAME,
          AndroidManifest.PERMISSION_ELEMENT_NAME,
          AndroidManifest.PERMISSION_GROUP_ELEMENT_NAME,
          AndroidManifest.PERMISSION_TREE_ELEMENT_NAME);

  public static AndroidManifest createArchivedManifest(AndroidManifest manifest) {
    checkNotNull(manifest);

    ManifestEditor editor =
        new ManifestEditor(createMinimalManifestTag(), BundleToolVersion.getCurrentVersion())
            .setPackage(manifest.getPackageName())
            .addMetaDataBoolean(META_DATA_KEY_ARCHIVED, true);

    MANIFEST_ATTRIBUTES_TO_KEEP.forEach(
        attrResourceId -> editor.copyManifestElementAndroidAttribute(manifest, attrResourceId));

    if (manifest.hasApplicationElement()) {
      APPLICATION_ATTRIBUTES_TO_KEEP.forEach(
          attrResourceId ->
              editor.copyApplicationElementAndroidAttribute(manifest, attrResourceId));
      getArchivedAllowBackup(manifest).ifPresent(editor::setAllowBackup);
    }

    manifest
        .getMetadataElement(META_DATA_GMS_VERSION)
        .ifPresent(editor::addApplicationChildElement);

    CHILDREN_ELEMENTS_TO_KEEP.forEach(
        elementName -> editor.copyChildrenElements(manifest, elementName));

    editor.addActivity(createReactivateActivity(manifest));
    editor.addReceiver(createUpdateBroadcastReceiver());
    addTvSupportIfRequired(editor, manifest);

    return editor.save();
  }

  private static Optional<Boolean> getArchivedAllowBackup(AndroidManifest manifest) {
    // Backup needs to be disabled if Backup Agent is provided and Full Backup Only is disabled.
    // Custom backup agent cannot be kept because it relies on app code that is not present in its
    // archived variant.
    return manifest.getAllowBackup().orElse(true)
            && (!manifest.hasBackupAgent() || manifest.getFullBackupOnly().orElse(false))
        ? manifest.getAllowBackup()
        : Optional.of(Boolean.FALSE);
  }

  private static XmlProtoNode createMinimalManifestTag() {
    return XmlProtoNode.createElementNode(
        XmlProtoElementBuilder.create("manifest")
            .addNamespaceDeclaration("android", ANDROID_NAMESPACE_URI)
            .build());
  }

  private static Activity createReactivateActivity(AndroidManifest manifest) {
    IntentFilter.Builder intentFilterBuilder =
        IntentFilter.builder().addActionName(MAIN_ACTION_NAME);
    // At least one of hasMainActivity and hasMainTvActivity is true, otherwise the app is headless
    // and archived APK cannot be generated.
    if (manifest.hasMainActivity()) {
      intentFilterBuilder.addCategoryName(LAUNCHER_CATEGORY_NAME);
    }
    if (manifest.hasMainTvActivity()) {
      intentFilterBuilder.addCategoryName(LEANBACK_LAUNCHER_CATEGORY_NAME);
    }

    return Activity.builder()
        .setName(REACTIVATE_ACTIVITY_NAME)
        .setTheme(HOLO_LIGHT_NO_ACTION_BAR_THEME)
        .setExported(true)
        .setExcludeFromRecents(true)
        .setStateNotNeeded(true)
        .setIntentFilter(intentFilterBuilder.build())
        .build();
  }

  private static Receiver createUpdateBroadcastReceiver() {
    return Receiver.builder()
        .setName(UPDATE_BROADCAST_RECEIVER_NAME)
        .setExported(true)
        .setIntentFilter(
            IntentFilter.builder().addActionName(MY_PACKAGE_REPLACED_ACTION_NAME).build())
        .build();
  }

  private static void addTvSupportIfRequired(
      ManifestEditor editor, AndroidManifest originalManifest) {
    if (!originalManifest.hasMainTvActivity()) {
      return;
    }

    editor.addUsesFeatureElement(LEANBACK_FEATURE_NAME, /* isRequired= */ false);
    editor.addUsesFeatureElement(TOUCHSCREEN_FEATURE_NAME, /* isRequired= */ false);
  }


  private ArchivedAndroidManifestUtils() {}
}