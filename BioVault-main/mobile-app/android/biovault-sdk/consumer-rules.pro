# Consumer ProGuard rules for biovault-sdk
# These rules are applied to consuming apps

-keep class com.biovault.sdk.** { *; }
-keep class com.biovault.sdk.BioVaultSDK { *; }
-keep class com.biovault.sdk.BioVaultSDK$* { *; }
