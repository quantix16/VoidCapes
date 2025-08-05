package net.litetex.capes.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * Manages encrypted storage of cape login credentials.
 */
public class CredentialsManager
{
	private static final Logger LOG = LoggerFactory.getLogger(CredentialsManager.class);
	private static final String ALGORITHM = "AES";
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 16;
	
	private final Path credentialsPath;
	private final Path keyPath;
	private final Gson gson;
	
	public CredentialsManager(final Path configDir)
	{
		this.credentialsPath = configDir.resolve("voidcapes_credentials.enc");
		this.keyPath = configDir.resolve("voidcapes_key.dat");
		this.gson = new GsonBuilder().setPrettyPrinting().create();
	}
	
	/**
	 * Stores credentials in an encrypted file.
	 */
	public void storeCredentials(final String username, final String password) throws Exception
	{
		final CredentialsData data = new CredentialsData(username, password);
		final String json = this.gson.toJson(data);
		
		final SecretKey key = getOrCreateKey();
		final byte[] encrypted = encrypt(json.getBytes(StandardCharsets.UTF_8), key);
		
		Files.createDirectories(this.credentialsPath.getParent());
		Files.write(this.credentialsPath, encrypted);
		
		LOG.info("[VoidCapes] Credentials stored securely");
	}
	
	/**
	 * Loads and decrypts stored credentials.
	 */
	public CredentialsData loadCredentials() throws Exception
	{
		if (!Files.exists(this.credentialsPath) || !Files.exists(this.keyPath))
		{
			return null;
		}
		
		final byte[] encrypted = Files.readAllBytes(this.credentialsPath);
		final SecretKey key = loadKey();
		final byte[] decrypted = decrypt(encrypted, key);
		final String json = new String(decrypted, StandardCharsets.UTF_8);
		
		return this.gson.fromJson(json, CredentialsData.class);
	}
	
	/**
	 * Checks if credentials exist.
	 */
	public boolean hasCredentials()
	{
		return Files.exists(this.credentialsPath) && Files.exists(this.keyPath);
	}
	
	/**
	 * Deletes stored credentials.
	 */
	public void deleteCredentials() throws IOException
	{
		if (Files.exists(this.credentialsPath))
		{
			Files.delete(this.credentialsPath);
		}
		if (Files.exists(this.keyPath))
		{
			Files.delete(this.keyPath);
		}
		LOG.info("[VoidCapes] Credentials deleted");
	}
	
	private SecretKey getOrCreateKey() throws Exception
	{
		if (Files.exists(this.keyPath))
		{
			return loadKey();
		}
		
		final KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
		keyGen.init(256);
		final SecretKey key = keyGen.generateKey();
		
		Files.createDirectories(this.keyPath.getParent());
		Files.write(this.keyPath, key.getEncoded());
		
		return key;
	}
	
	private SecretKey loadKey() throws IOException
	{
		final byte[] keyBytes = Files.readAllBytes(this.keyPath);
		return new SecretKeySpec(keyBytes, ALGORITHM);
	}
	
	private byte[] encrypt(final byte[] data, final SecretKey key) throws Exception
	{
		final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		final byte[] iv = new byte[GCM_IV_LENGTH];
		new SecureRandom().nextBytes(iv);
		
		final GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
		cipher.init(Cipher.ENCRYPT_MODE, key, spec);
		
		final byte[] encrypted = cipher.doFinal(data);
		final byte[] result = new byte[GCM_IV_LENGTH + encrypted.length];
		System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
		System.arraycopy(encrypted, 0, result, GCM_IV_LENGTH, encrypted.length);
		
		return result;
	}
	
	private byte[] decrypt(final byte[] encryptedData, final SecretKey key) throws Exception
	{
		final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		
		final byte[] iv = new byte[GCM_IV_LENGTH];
		System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH);
		
		final GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
		cipher.init(Cipher.DECRYPT_MODE, key, spec);
		
		final byte[] encrypted = new byte[encryptedData.length - GCM_IV_LENGTH];
		System.arraycopy(encryptedData, GCM_IV_LENGTH, encrypted, 0, encrypted.length);
		
		return cipher.doFinal(encrypted);
	}
	
	/**
	 * Data class for storing credentials.
	 */
	public static class CredentialsData
	{
		private final String username;
		private final String password;
		private final long timestamp;
		
		public CredentialsData(final String username, final String password)
		{
			this.username = username;
			this.password = password;
			this.timestamp = System.currentTimeMillis();
		}
		
		public String getUsername()
		{
			return this.username;
		}
		
		public String getPassword()
		{
			return this.password;
		}
		
		public long getTimestamp()
		{
			return this.timestamp;
		}
	}
}
