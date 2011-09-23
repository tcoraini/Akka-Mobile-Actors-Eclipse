#!/bin/bash

generated_file=$2
list_of_nodes=`grep '[^ ]' $1 | grep -v \#`

number_of_nodes=`grep '[^ ]' $1 | grep -v \# | wc -l`

echo -e "cluster {\n"
echo -e -n "\tnodes = [ \"node_1\""

if [ $number_of_nodes == 1 ]; then
    echo -e " ]\n"
else
    echo ","
fi

index=2
while [ $index -lt $number_of_nodes ];
do
    echo -e "\t\t\"node_$index\","
    index=`expr $index + 1`
done

if [ "$number_of_nodes" -gt "1" ]; then
    echo -e "\t\t\"node_$index\" ]\n"
fi

index=1
for node in $list_of_nodes; do
    echo -e "\tnode_$index {"
    echo -e "\t\thostname = \"$node\""
    echo -e "\t\tport = 1810"
    if [ $index == 1 ]; then
	echo -e "\t\tname-server on"
    fi
    echo -e "\t}\n"
    index=`expr $index + 1`
done

cat common-config