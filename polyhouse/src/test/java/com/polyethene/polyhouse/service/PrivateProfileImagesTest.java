package com.polyethene.polyhouse.service;
import com.polyethene.polyhouse.common.exception.BusinessException;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** 私有存储防护与持久删除重试；仅操作本次 target 子目录。 */
class PrivateProfileImagesTest {
 private Path root,privateRoot;private PrivateProfileImages storage;
 @BeforeEach void setup()throws Exception{root=Path.of("target").toAbsolutePath().resolve("p4-storage-"+UUID.randomUUID());Files.createDirectories(root);privateRoot=root.resolve("avatars-pending");storage=new PrivateProfileImages(root.resolve("avatars").toString());}
 @AfterEach void cleanup()throws Exception{Path boundary=Path.of("target").toAbsolutePath().normalize();if(!root.normalize().startsWith(boundary)||!root.getFileName().toString().startsWith("p4-storage-"))throw new IllegalStateException("unsafe cleanup");try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
 @Test void writesOnlySiblingPrivateDirectoryAndRejectsTraversal(){String name=storage.save(new byte[]{1,2});assertThat(Files.exists(root.resolve("avatars").resolve(name))).isFalse();assertThat(storage.read(name)).containsExactly(1,2);assertThatThrownBy(()->storage.read("../outside")).isInstanceOf(BusinessException.class);}
 @Test void oversizedOrEmptyPayloadCannotCreateFiles(){for(byte[] bytes:new byte[][]{new byte[0],new byte[256*1024+1]})assertThatThrownBy(()->storage.save(bytes)).isInstanceOf(BusinessException.class);assertThat(Files.exists(privateRoot)).isFalse();}
 @Test void fullPrivateDirectoryRefusesAnotherFile()throws Exception{Files.createDirectories(privateRoot);for(int i=0;i<512;i++)Files.write(privateRoot.resolve(String.format("%032x.jpg",i)),new byte[0]);assertThatThrownBy(()->storage.save(new byte[]{1})).isInstanceOf(BusinessException.class);try(var files=Files.list(privateRoot)){assertThat(files.count()).isEqualTo(512);}}
 @Test void failedDeletePersistsMarkerAndLaterSweepCanRecover()throws Exception{String name=storage.save(new byte[]{1});Path file=privateRoot.resolve(name);Files.delete(file);Files.createDirectory(file);Path blocker=Files.write(file.resolve("fixture"),new byte[0]);storage.deleteEventually(name);assertThat(Files.exists(privateRoot.resolve(".cleanup").resolve(name))).isTrue();Files.delete(blocker);storage.sweep(key->false);assertThat(Files.exists(file)).isFalse();assertThat(Files.exists(privateRoot.resolve(".cleanup").resolve(name))).isFalse();}
 @Test void referencedFileIsNeverDeletedByStaleMarker()throws Exception{String name=storage.save(new byte[]{1});Files.createDirectory(privateRoot.resolve(".cleanup"));Files.write(privateRoot.resolve(".cleanup").resolve(name),new byte[0]);storage.sweep(key->true);assertThat(Files.exists(privateRoot.resolve(name))).isTrue();}
}
