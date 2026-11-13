# ScanState and MonitoringStatus save these classes with Java serialization, and read them back
# after the app is updated. Keep the names they are saved under, their fields and their
# serialVersionUID, so an obfuscated build reads what an earlier one wrote: a field renamed by
# R8 would come back null without any error.
-keepnames class org.altbeacon.beacon.** implements java.io.Serializable
-keepclassmembers class org.altbeacon.beacon.** implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
