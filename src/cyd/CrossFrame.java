package cyd;

import java.io.*;
import java.util.*;

public class CrossFrame
{
	public static void feature_label(String dir_path, String result_path)
			throws Exception
	{
		HashSet<String> feat_set = new HashSet<String>();

		int index = 0;
		
		File dir = new File(dir_path);
		File[] files = dir.listFiles();
		for (File file : files)
		{
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line = null;
			while ((line = br.readLine()) != null)
			{
				String[] strs = line.split(" ");
				for (int i = 2; i < strs.length; ++i)
				{
					String str = strs[i];
					str = str.substring(0, str.indexOf("="));
					feat_set.add(str);
				}
			}
			br.close();
			System.out.println(++index);
		}
		FileWriter fw = new FileWriter(result_path);
		for (String str : feat_set)
		{
			fw.write(str + "\n");
		}
		fw.close();
	}

	public static void trigger_rate(String dir_path)
			throws Exception
	{
		int index = 0;
		int child = 0,  parent = 0;
		File dir = new File(dir_path);
		File[] files = dir.listFiles();
		for (File file : files)
		{
			BufferedReader br = new BufferedReader(new FileReader(file));
			String line = null;
			while ((line = br.readLine()) != null)
			{
				String[] strs = line.split(" ");
				if (strs[1].equals("O") == false)
				{
					++child;
				}
				++parent;
			}
			br.close();
			System.out.println(++index);
		}
		System.out.println((double) child / parent);
		
	}
	
	public static void main(String[] args) throws Exception
	{
		String train_path = "./cyd/feature/train_feat_only_train_lu.txt";
		String dir_path = "./tmp/featDir";
//		feature_label(dir_path, "./cyd/feature/feature_label.txt");
		
		trigger_rate(dir_path);
		System.exit(0);
	}

}
