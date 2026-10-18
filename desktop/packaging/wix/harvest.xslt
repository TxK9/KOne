<?xml version="1.0" encoding="utf-8"?>
<!-- Takes KOne.exe out of what heat harvests from the app image: KOne.wxs installs it itself,
     with its shortcuts and the kone:// link. -->
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:wix="http://schemas.microsoft.com/wix/2006/wi"
    xmlns="http://schemas.microsoft.com/wix/2006/wi"
    exclude-result-prefixes="wix">
  <xsl:output method="xml" indent="yes"/>
  <xsl:key name="launcher" match="wix:Component[wix:File[substring(@Source, string-length(@Source) - 8) = '\KOne.exe']]" use="@Id"/>
  <xsl:template match="@*|node()">
    <xsl:copy><xsl:apply-templates select="@*|node()"/></xsl:copy>
  </xsl:template>
  <xsl:template match="wix:Component[key('launcher', @Id)]"/>
  <xsl:template match="wix:ComponentRef[key('launcher', @Id)]"/>
</xsl:stylesheet>
